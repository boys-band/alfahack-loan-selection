#!/usr/bin/env python3
"""Render actual PostgreSQL tables and FKs as reproducible Graphviz ER diagrams."""
import html
import json
import shutil
import subprocess
import time
import uuid

from validate_database import IMAGE, ROOT, SERVICES

OUTPUT = ROOT / 'database' / 'diagrams'
TITLES = {
    'applications': ('Заявки и процесс', '#2563eb', '#eff6ff'),
    'catalog': ('Кредитные продукты', '#0f766e', '#f0fdfa'),
    'verification': ('Проверки и источники', '#b45309', '#fffbeb'),
    'decision': ('Скоринг и решения', '#7c3aed', '#f5f3ff'),
    'agreements': ('Договоры и подпись', '#be185d', '#fdf2f8'),
    'disbursement': ('Выдача и проводки', '#047857', '#ecfdf5'),
}

# pg_catalog is authoritative: arrays preserve composite-key column order.
INTROSPECT = """
WITH tables AS (
    SELECT c.oid, c.relname AS name
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind='r' AND c.relname<>'schema_migrations'
), columns AS (
    SELECT t.name AS table_name, a.attnum AS position, a.attname AS name,
           format_type(a.atttypid,a.atttypmod) AS type,
           a.attnotnull AS not_null, a.attgenerated<>'' AS generated,
           pg_get_expr(d.adbin,d.adrelid) AS default_expression
    FROM tables t JOIN pg_attribute a ON a.attrelid=t.oid
    LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
    WHERE a.attnum>0 AND NOT a.attisdropped
), constraints AS (
    SELECT t.name AS table_name, c.conname AS name, c.contype AS kind,
           ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,pos)
                 JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num
                 ORDER BY k.pos) AS columns,
           p.relname AS parent_table,
           ARRAY(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(num,pos)
                 JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num
                 ORDER BY k.pos) AS parent_columns,
           c.condeferrable AS deferrable, c.condeferred AS initially_deferred,
           pg_get_constraintdef(c.oid) AS definition
    FROM tables t JOIN pg_constraint c ON c.conrelid=t.oid
    LEFT JOIN pg_class p ON p.oid=c.confrelid
), indexes AS (
    SELECT t.name AS table_name, i.relname AS name, x.indisunique AS unique,
           pg_get_indexdef(x.indexrelid) AS definition,
           pg_get_expr(x.indpred,x.indrelid) AS predicate
    FROM tables t JOIN pg_index x ON x.indrelid=t.oid
    JOIN pg_class i ON i.oid=x.indexrelid
)
SELECT jsonb_build_object(
    'tables', (SELECT jsonb_agg(jsonb_build_object('name',t.name,
        'columns',(SELECT jsonb_agg(to_jsonb(c)-'table_name' ORDER BY position)
                   FROM columns c WHERE c.table_name=t.name),
        'constraints',COALESCE((SELECT jsonb_agg(to_jsonb(c)-'table_name' ORDER BY name)
                   FROM constraints c WHERE c.table_name=t.name),'[]'::jsonb),
        'indexes',COALESCE((SELECT jsonb_agg(to_jsonb(i)-'table_name' ORDER BY name)
                   FROM indexes i WHERE i.table_name=t.name),'[]'::jsonb)) ORDER BY t.name)
        FROM tables t))::text;
"""


def run(args, **kwargs):
    result = subprocess.run(args, text=True, capture_output=True, check=False, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stderr[-4000:]}')
    return result.stdout


def quote(value):
    return json.dumps(str(value), ensure_ascii=False)


def node_id(service, table):
    return f'{service}__{table}'


def foreign_keys(table):
    return [c for c in table['constraints'] if c['kind'] == 'f']


def table_label(service, table, compact):
    _, color, pale = TITLES[service]
    keys = {}
    for constraint in table['constraints']:
        marker = {'p': 'PK', 'f': 'FK', 'u': 'UQ'}.get(constraint['kind'])
        if marker:
            for col in constraint['columns']:
                keys.setdefault(col, set()).add(marker)
    if compact:
        primary = next((c['columns'] for c in table['constraints'] if c['kind'] == 'p'), [])
        description = 'PK: ' + ', '.join(primary)
        return (f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="9" COLOR="{color}">'
                f'<TR><TD BGCOLOR="{pale}"><B>{table["name"]}</B></TD></TR>'
                f'<TR><TD ALIGN="LEFT"><FONT POINT-SIZE="10">{description}</FONT></TD></TR></TABLE>>')
    rows = [f'<TR><TD COLSPAN="3" ALIGN="LEFT" BGCOLOR="{pale}"><FONT COLOR="{color}" POINT-SIZE="15"><B>{table["name"]}</B></FONT></TD></TR>',
            '<TR><TD BGCOLOR="#f1f5f9"><B>Ключ</B></TD><TD ALIGN="LEFT" BGCOLOR="#f1f5f9"><B>Поле</B></TD><TD ALIGN="LEFT" BGCOLOR="#f1f5f9"><B>Тип</B></TD></TR>']
    for column in table['columns']:
        markers = '/'.join(x for x in ('PK', 'FK', 'UQ') if x in keys.get(column['name'], set()))
        suffix = '' if column['not_null'] else ' ?'
        if column['generated']:
            suffix += ' [G]'
        bg = pale if 'PK' in keys.get(column['name'], set()) else '#ffffff'
        rows.append(f'<TR><TD BGCOLOR="{bg}"><FONT POINT-SIZE="10">{markers or "&#160;"}</FONT></TD>'
                    f'<TD ALIGN="LEFT" BGCOLOR="{bg}">{html.escape(column["name"])}</TD>'
                    f'<TD ALIGN="LEFT" BGCOLOR="{bg}"><FONT POINT-SIZE="11">{html.escape(column["type"] + suffix)}</FONT></TD></TR>')
    return '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="6" COLOR="#cbd5e1">' + ''.join(rows) + '</TABLE>>'


def make_graph(schemas, service=None):
    compact = service is None
    selected = SERVICES if compact else (service,)
    title = 'ER-модель · шесть независимых баз данных' if compact else f'ER · {service} · {TITLES[service][0]}'
    subtitle = ('Стрелки: дочерняя таблица → родительская. Пунктир: отложенный FK. Межбазовых FK нет.'
                if compact else 'PK — первичный ключ · FK — внешний ключ · UQ — участие в UNIQUE · ? — NULL · [G] — вычисляемое поле')
    lines = ['digraph ER {',
             f'graph [rankdir=LR, bgcolor="#ffffff", fontname="DejaVu Sans", fontsize=22, pad="0.35", nodesep="0.4", ranksep="0.8", splines=polyline, outputorder=edgesfirst, labelloc=t, label={quote(title + chr(10) + subtitle)}, size="32,28", dpi=120];',
             'node [shape=plain, fontname="DejaVu Sans", fontsize=12];',
             'edge [color="#64748b", arrowsize=0.7, fontname="DejaVu Sans", fontsize=10];']
    for name in selected:
        _, color, pale = TITLES[name]
        if compact:
            lines.extend([f'subgraph cluster_{name} {{',
                          f'label={quote("alfa_" + name + " · " + TITLES[name][0])}; color={quote(color)}; style="rounded"; bgcolor={quote(pale)}; fontsize=18; margin=20;'])
        for table in schemas[name]['tables']:
            lines.append(f'{node_id(name, table["name"])} [label={table_label(name, table, compact)}];')
        # Overview merges multiple composite FKs between a pair. Detailed diagrams
        # retain every constraint and its full ordered column mapping.
        grouped = {}
        for table in schemas[name]['tables']:
            for fk in foreign_keys(table):
                grouped.setdefault((table['name'], fk['parent_table']), []).append(fk)
        for (child, parent), fks in grouped.items():
            for group in ([fks] if compact else [[fk] for fk in fks]):
                deferred = all(fk['initially_deferred'] for fk in group)
                attrs = [f'style={"dashed" if deferred else "solid"}', f'color={quote(color if deferred else "#64748b")}']
                if compact:
                    if len(group) > 1:
                        attrs.append(f'label={quote(str(len(group)) + " FK")}')
                else:
                    fk = group[0]
                    label = ', '.join(fk['columns']) + '\n→ ' + ', '.join(fk['parent_columns'])
                    if fk['initially_deferred']:
                        label += '\nDEFERRED'
                    attrs.extend([f'label={quote(label)}', f'tooltip={quote(fk["name"])}'])
                # Current pointers and reciprocal audit pointers should not
                # reverse the main ownership layout.
                if (name == 'applications' and child == 'applications') or (child, parent) in {
                    ('agreements', 'signatures'), ('disbursements', 'credit_entries'),
                    ('decisions', 'offers'), ('decisions', 'manual_review_actions'),
                    ('manual_review_cases', 'manual_review_actions')}:
                    attrs.append('constraint=false')
                lines.append(f'{node_id(name, child)} -> {node_id(name, parent)} [{", ".join(attrs)}];')
        if compact:
            lines.append('}')
    lines.append('}')
    return '\n'.join(lines) + '\n'


def write_readme(schemas):
    count = sum(len(schema['tables']) for schema in schemas.values())
    fk_count = sum(len(foreign_keys(t)) for schema in schemas.values() for t in schema['tables'])
    text = f'''# ER-модель баз данных

{count} таблица, {fk_count} внешних ключей, шесть отдельных БД PostgreSQL. Схемы автоматически построены по системному каталогу после применения SQL-миграций.

![Общая ER-модель](overview.png)

На общей карте указаны таблицы и первичные ключи. Стрелка идет от дочерней таблицы к родительской; пунктир обозначает FK, проверяемый в конце транзакции. Несколько FK между одной парой таблиц объединены с указанием количества. Межбазовых FK нет: UUID и снимки данных соседних сервисов передаются через API.

## Подробные схемы

SVG сохраняет четкость при увеличении. В подробной схеме показаны все поля, типы и каждый составной FK с точным порядком столбцов.

| Сервис | Назначение | Таблиц | Схема | Изображение | Исходник |
| --- | --- | ---: | --- | --- | --- |
'''
    for service in SERVICES:
        text += f'| `{service}` | {TITLES[service][0]} | {len(schemas[service]["tables"])} | [SVG]({service}.svg) | [PNG]({service}.png) | [DOT]({service}.dot) |\n'
    text += '''
`PK` — первичный ключ; `FK` — внешний ключ; `UQ` — поле входит в ограничение UNIQUE; `?` — допускает NULL; `[G]` — generated column. Составной PK/UNIQUE действует на сочетание полей; отметка UQ у поля не означает уникальность этого поля отдельно.

Изолированные таблицы изображены без искусственных связей: например, verification_results хранит снимок проверки, source_fixtures — изменяемые моки источников. Одинаковый application_id в разных БД является логической ссылкой, а не SQL FK.

В пяти изменяющих сервисах есть собственная idempotency_records. Техническая schema_migrations есть в каждой БД и исключена из ER-схем. CHECK, частичные уникальные индексы и триггеры задают дополнительные правила; диаграммы не заменяют [SQL-миграции](../../services). Определения ограничений и индексов сохранены в [schema.json](schema.json).

## Перегенерация

Нужны Python 3, Docker и Graphviz (`dot`):

```bash
python3 scripts/generate_er_diagrams.py
```

Скрипт создает временный PostgreSQL без сети, применяет миграции и seed, считывает pg_catalog, сохраняет DOT/SVG/PNG и удаляет контейнер. Данные работающего проекта не используются. Общая схема также доступна в [SVG](overview.svg) и [DOT](overview.dot).
'''
    (OUTPUT / 'README.md').write_text(text)


def main():
    if not shutil.which('dot'):
        raise SystemExit('Graphviz is required: install dot before generating diagrams.')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    container = 'alfa-er-' + uuid.uuid4().hex[:12]
    started = False
    try:
        run(['docker', 'run', '--detach', '--rm', '--name', container, '--network', 'none',
             '--tmpfs', '/var/lib/postgresql/data:rw', '-e', 'POSTGRES_PASSWORD=temporary-er-model',
             '-v', str(ROOT / 'database') + ':/schema/database:ro',
             '-v', str(ROOT / 'services') + ':/schema/services:ro', IMAGE])
        started = True
        deadline = time.monotonic() + 40
        while True:
            ready = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                                    'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres'],
                                   capture_output=True)
            if ready.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('PostgreSQL startup timed out')
            time.sleep(0.2)
        run(['docker', 'exec', container, 'bash', '/schema/database/bootstrap.sh'])
        schemas = {}
        for service in SERVICES:
            schemas[service] = json.loads(run(['docker', 'exec', '-i', container, 'psql', '-X', '-At',
                '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'alfa_' + service], input=INTROSPECT))
        (OUTPUT / 'schema.json').write_text(json.dumps(schemas, ensure_ascii=False, indent=2) + '\n')
    finally:
        if started:
            run(['docker', 'rm', '-f', container])
    for service in (None, *SERVICES):
        stem = service or 'overview'
        dot = OUTPUT / (stem + '.dot')
        dot.write_text(make_graph(schemas, service))
        for extension in ('svg', 'png'):
            run(['dot', '-T' + extension, str(dot), '-o', str(OUTPUT / (stem + '.' + extension))], timeout=60)
    write_readme(schemas)
    count = sum(len(schema['tables']) for schema in schemas.values())
    fk_count = sum(len(foreign_keys(t)) for schema in schemas.values() for t in schema['tables'])
    print(f'Generated overview and 6 detailed ER diagrams: {count} tables, {fk_count} FKs. Output: {OUTPUT}')


if __name__ == '__main__':
    main()
