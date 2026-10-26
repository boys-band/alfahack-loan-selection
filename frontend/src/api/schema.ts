import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import schema from '../../contract/schemas.json';
import type { ErrorObject } from 'ajv';
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema(schema);
export class ValidationError extends Error {
  constructor(public errors: ErrorObject[]) {
    super(
      'Проверьте поля формы: ' +
        errors.map((e) => `${e.instancePath || '/'} ${e.message}`).join('; '),
    );
  }
}
export function validate(name: string, value: unknown) {
  const check = ajv.getSchema(`urn:alfa:public#/$defs/${name}`)!;
  if (!check(value)) throw new ValidationError(check.errors || []);
}
