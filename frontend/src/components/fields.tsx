import { Input, Select, Textarea } from './validated-form';
import { useBlocker } from 'react-router-dom';
import type { Fields } from '../core/forms';
import type { Product, Purpose } from '../api/generated';
import { fieldLabels } from '../core/forms';
import { purposeLabels } from '../core/state';
import { useEffect, useRef } from 'react';
export function useDirty(dirty: boolean) {
  const blocker = useBlocker(dirty);
  useEffect(() => {
    if (blocker.state === 'blocked') {
      if (window.confirm('Есть несохранённые изменения. Уйти без сохранения?')) blocker.proceed();
      else blocker.reset();
    }
  }, [blocker]);
  useEffect(() => {
    if (!dirty) return;
    const before = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', before);
    return () => window.removeEventListener('beforeunload', before);
  }, [dirty]);
}
export function DataFields({
  fields,
  setFields,
  paths = Object.keys(fieldLabels),
  disabled = false,
  errors = [],
  errorKey,
  requiredPaths = [],
  purposeCodes = [],
  productLimits,
}: {
  fields: Fields;
  setFields: (f: Fields) => void;
  paths?: string[];
  disabled?: boolean;
  errors?: { path: string; message: string }[];
  errorKey?: unknown;
  requiredPaths?: string[];
  purposeCodes?: readonly Purpose[];
  productLimits?: Pick<
    Product,
    'minAmountRub' | 'maxAmountRub' | 'minTermMonths' | 'maxTermMonths'
  >;
}) {
  const key = errorKey ?? JSON.stringify(errors);
  const errorValues = useRef({ key, fields: { ...fields } });
  if (errorValues.current.key !== key) errorValues.current = { key, fields: { ...fields } };
  return (
    <div className="form-grid">
      {paths.map((path) => {
        const id = 'f' + path.replaceAll('/', '-');
        const label = fieldLabels[path];
        if (!label)
          return (
            <div className="alert" key={path}>
              Не удалось определить поле для уточнения.{' '}
              {errors.find((e) => e.path === path)?.message ||
                'Обратитесь к сотруднику для уточнения поля.'}
            </div>
          );
        const error = errors.find(
          (e) => e.path === path && fields[path] === errorValues.current.fields[path],
        );
        const props = {
          id,
          disabled,
          required: requiredPaths.includes(path),
          value: fields[path] || '',
          onChange: (
            e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
          ) => setFields({ ...fields, [path]: e.target.value }),
          'aria-invalid': !!error,
          'aria-describedby': error ? id + '-error' : undefined,
        };
        return (
          <div
            className={
              'field ' +
              (path.includes('Description') || path.includes('Explanation') ? 'wide' : '')
            }
            key={path}
          >
            <label htmlFor={id}>{label}</label>
            {path === '/purpose' ? (
              <Select
                {...props}
                disabled={disabled || !purposeCodes.length}
                data-unavailable-message="Выберите цель кредита, доступную для выбранного продукта."
              >
                <option value="">
                  {purposeCodes.length ? 'Выберите цель' : 'Сначала выберите продукт'}
                </option>
                {fields[path] && !purposeCodes.some((code) => code === fields[path]) && (
                  <option value={fields[path]} disabled>
                    {purposeLabels[fields[path] as Purpose]} — недоступна для продукта
                  </option>
                )}
                {purposeCodes.map((code) => (
                  <option key={code} value={code}>
                    {purposeLabels[code]}
                  </option>
                ))}
              </Select>
            ) : path.startsWith('/additionalInfo/') && !path.endsWith('Rub') ? (
              <Textarea {...props} maxLength={2000} rows={3} />
            ) : (
              <Input
                {...props}
                inputMode="numeric"
                type="number"
                data-digits-only="true"
                min={
                  path === '/amountRub'
                    ? (productLimits?.minAmountRub ?? 1)
                    : path === '/termMonths'
                      ? (productLimits?.minTermMonths ?? 1)
                      : 0
                }
                max={
                  path === '/termMonths'
                    ? (productLimits?.maxTermMonths ?? 120)
                    : path === '/amountRub'
                      ? (productLimits?.maxAmountRub ?? 1000000000)
                      : 1000000000000
                }
                step={1}
              />
            )}{' '}
            {error && (
              <small id={id + '-error'} className="field-error">
                {error.message}
              </small>
            )}
          </div>
        );
      })}
    </div>
  );
}
