import Ajv from 'ajv';
import { expect, test } from 'vitest';
import schema from '../../../protocol/raw-observation-v1.schema.json';
import fixture from '../../../protocol/fixtures/raw-observation-v1.json';

test('shared BLE fixture is lossless and conforms to its separate protocol schema', () => {
  const validate = new Ajv({ strictTypes: false }).compile(schema);
  expect(validate(fixture), JSON.stringify(validate.errors)).toBe(true);
  expect(BigInt(fixture.sequence)).toBeGreaterThan(BigInt(Number.MAX_SAFE_INTEGER));
  expect(validate({ ...fixture, sequence: Number(fixture.sequence) })).toBe(false);
  expect(validate({ ...fixture, clock_status: 'known' })).toBe(false);
  expect(validate({ ...fixture, visits: [] })).toBe(false);
});
