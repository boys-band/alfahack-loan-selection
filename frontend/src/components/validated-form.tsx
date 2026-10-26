import {
  createContext,
  createElement,
  useContext,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FormHTMLAttributes,
  type InputHTMLAttributes,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
  type FieldsetHTMLAttributes,
  type HTMLAttributes,
} from 'react';

type Issue = { message: string; related?: string[] };
export type FormIssues = Record<string, Issue>;
const Validation = createContext<Record<string, string>>({});
type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

function constraintMessage(field: Control): string | undefined {
  if (!field.willValidate) return;
  if (field instanceof HTMLSelectElement && field.value && field.selectedOptions[0]?.disabled)
    return field.dataset.unavailableMessage || 'Выберите доступное значение.';
  if (field.validity.badInput || field.validity.typeMismatch) return 'Проверьте формат значения.';
  if (field.required && !field.value.trim())
    return (
      field.dataset.requiredMessage ||
      (field instanceof HTMLSelectElement ? 'Выберите значение.' : 'Заполните поле.')
    );
  if (field.validity.valueMissing)
    return field instanceof HTMLInputElement && field.type === 'checkbox'
      ? 'Подтвердите согласие.'
      : 'Выберите значение.';
  if (field.validity.patternMismatch)
    return field.dataset.formatMessage || 'Проверьте формат значения.';
  if (field instanceof HTMLInputElement) {
    if (field.validity.rangeUnderflow || field.validity.rangeOverflow)
      return field.min && field.max
        ? `Укажите значение от ${field.min} до ${field.max}.`
        : field.validity.rangeUnderflow
          ? `Укажите значение не меньше ${field.min}.`
          : `Укажите значение не больше ${field.max}.`;
    if (field.validity.stepMismatch) return 'Введите целое число.';
    if (field.dataset.digitsOnly && field.value && !/^\d+$/.test(field.value))
      return 'Введите целое неотрицательное число.';
  }
  if (!(field instanceof HTMLSelectElement)) {
    const length = Array.from(field.value).length;
    const minimum = field.minLength;
    const maximum = field.dataset.maxLength ? Number(field.dataset.maxLength) : field.maxLength;
    if (field.value && minimum > 0 && length < minimum)
      return (
        field.dataset.lengthMessage ||
        (maximum > 0
          ? `Введите от ${minimum} до ${maximum} символов.`
          : `Введите не меньше ${minimum} символов.`)
      );
    if (maximum >= 0 && length > maximum)
      return field.dataset.lengthMessage || `Введите не больше ${maximum} символов.`;
  }
}

export function ValidatedForm({
  children,
  onSubmit,
  onReset,
  onChangeCapture,
  validate,
  ...props
}: FormHTMLAttributes<HTMLFormElement> & { validate?: () => FormIssues }) {
  const ref = useRef<HTMLFormElement>(null);
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [submitted, setSubmitted] = useState(false);
  const [visible, setVisible] = useState<Record<string, string>>({});
  function collect() {
    const issues: FormIssues = {};
    const form = ref.current;
    if (!form) return issues;
    for (const field of Array.from(form.elements)) {
      if (
        !field.id ||
        !(
          field instanceof HTMLInputElement ||
          field instanceof HTMLSelectElement ||
          field instanceof HTMLTextAreaElement
        )
      )
        continue;
      const message = constraintMessage(field);
      if (message) issues[field.id] = { message };
    }
    for (const [id, issue] of Object.entries(validate?.() || {})) {
      const field = form.ownerDocument.getElementById(id);
      if (field && form.contains(field) && !field.matches(':disabled') && !issues[id])
        issues[id] = issue;
    }
    return issues;
  }
  useLayoutEffect(() => {
    const messages = Object.fromEntries(
      Object.entries(collect())
        .filter(
          ([id, issue]) =>
            submitted || touched.has(id) || issue.related?.some((key) => touched.has(key)),
        )
        .map(([id, issue]) => [id, issue.message]),
    );
    setVisible((previous) =>
      JSON.stringify(previous) === JSON.stringify(messages) ? previous : messages,
    );
  });
  return (
    <Validation.Provider value={visible}>
      <form
        {...props}
        ref={ref}
        noValidate
        onReset={(event) => {
          onReset?.(event);
          setTouched(new Set());
          setSubmitted(false);
          setVisible({});
        }}
        onChangeCapture={(event) => {
          const target = event.target;
          if (target instanceof HTMLElement) {
            const ids = [target.id, target.closest('fieldset')?.id].filter(
              (id): id is string => !!id,
            );
            setTouched((previous) => new Set([...previous, ...ids]));
          }
          onChangeCapture?.(event);
        }}
        onSubmit={(event) => {
          const issues = collect();
          if (Object.keys(issues).length) {
            event.preventDefault();
            setSubmitted(true);
            const field = ref.current?.ownerDocument.getElementById(Object.keys(issues)[0]);
            const focus =
              field instanceof HTMLFieldSetElement
                ? field.querySelector<HTMLElement>('input, select, textarea')
                : field;
            focus?.focus();
            return;
          }
          setSubmitted(false);
          setTouched(new Set());
          onSubmit?.(event);
        }}
      >
        {children}
      </form>
    </Validation.Provider>
  );
}

function FieldControl({ tag, props }: { tag: string; props: HTMLAttributes<HTMLElement> }) {
  const generatedId = useId();
  const id = props.id || generatedId;
  const message = useContext(Validation)[id];
  const description =
    [props['aria-describedby'], message ? id + '-validation' : null].filter(Boolean).join(' ') ||
    undefined;
  const feedback = message ? (
    <small id={id + '-validation'} className="field-error">
      {message}
    </small>
  ) : null;
  const attributes = {
    ...props,
    id,
    'aria-invalid': message ? true : props['aria-invalid'],
    'aria-describedby': description,
  };
  return tag === 'fieldset' ? (
    createElement(tag, attributes, props.children, feedback)
  ) : (
    <>
      {createElement(tag, attributes)}
      {feedback}
    </>
  );
}
export const Input = (props: InputHTMLAttributes<HTMLInputElement>) => (
  <FieldControl tag="input" props={props} />
);
export const Select = (props: SelectHTMLAttributes<HTMLSelectElement>) => (
  <FieldControl tag="select" props={props} />
);
export const Textarea = (props: TextareaHTMLAttributes<HTMLTextAreaElement>) => (
  <FieldControl tag="textarea" props={props} />
);
export const Fieldset = (props: FieldsetHTMLAttributes<HTMLFieldSetElement>) => (
  <FieldControl tag="fieldset" props={props} />
);
