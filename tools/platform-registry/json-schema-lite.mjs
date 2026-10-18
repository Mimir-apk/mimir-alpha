import { isDeepStrictEqual } from "node:util";

function escapePointer(value) {
  return String(value).replaceAll("~", "~0").replaceAll("/", "~1");
}

function resolvePointer(rootSchema, pointer) {
  if (pointer === "#") return rootSchema;
  if (!pointer.startsWith("#/")) throw new Error(`Only local JSON Schema references are supported: ${pointer}`);
  return pointer
    .slice(2)
    .split("/")
    .map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"))
    .reduce((value, part) => value?.[part], rootSchema);
}

function jsonType(value) {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value;
}

function typeMatches(value, expected) {
  const observed = jsonType(value);
  if (expected === "number") return observed === "integer" || observed === "number";
  if (expected === "object") return observed === "object";
  return observed === expected;
}

export function validateJsonSchema(instance, schema, { maxErrors = 200 } = {}) {
  const errors = [];

  function add(path, message) {
    if (errors.length < maxErrors) errors.push(`${path}: ${message}`);
  }

  function visit(value, currentSchema, path) {
    if (errors.length >= maxErrors) return;
    if (currentSchema === true || currentSchema === undefined) return;
    if (currentSchema === false) {
      add(path, "schema rejects all values");
      return;
    }
    if (!currentSchema || typeof currentSchema !== "object") {
      add(path, "invalid schema node");
      return;
    }

    if (currentSchema.$ref) {
      const target = resolvePointer(schema, currentSchema.$ref);
      if (target === undefined) add(path, `unresolved reference ${currentSchema.$ref}`);
      else visit(value, target, path);
    }

    if (currentSchema.oneOf) {
      let matchCount = 0;
      for (const candidate of currentSchema.oneOf) {
        const errorCount = errors.length;
        visit(value, candidate, path);
        if (errors.length === errorCount) matchCount += 1;
        errors.length = errorCount;
      }
      if (matchCount !== 1) add(path, `expected exactly one oneOf branch, observed ${matchCount}`);
    }

    if (currentSchema.const !== undefined && !isDeepStrictEqual(value, currentSchema.const)) {
      add(path, `expected constant ${JSON.stringify(currentSchema.const)}`);
    }
    if (currentSchema.enum && !currentSchema.enum.some((candidate) => isDeepStrictEqual(value, candidate))) {
      add(path, `value ${JSON.stringify(value)} is not in enum`);
    }

    if (currentSchema.type !== undefined) {
      const expectedTypes = Array.isArray(currentSchema.type) ? currentSchema.type : [currentSchema.type];
      if (!expectedTypes.some((expected) => typeMatches(value, expected))) {
        add(path, `expected type ${expectedTypes.join("|")}, observed ${jsonType(value)}`);
        return;
      }
    }

    if (typeof value === "string") {
      if (currentSchema.minLength !== undefined && value.length < currentSchema.minLength) {
        add(path, `string is shorter than ${currentSchema.minLength}`);
      }
      if (currentSchema.pattern !== undefined && !new RegExp(currentSchema.pattern, "u").test(value)) {
        add(path, `string does not match ${currentSchema.pattern}`);
      }
    }

    if (typeof value === "number") {
      if (currentSchema.minimum !== undefined && value < currentSchema.minimum) {
        add(path, `number is less than ${currentSchema.minimum}`);
      }
      if (currentSchema.maximum !== undefined && value > currentSchema.maximum) {
        add(path, `number is greater than ${currentSchema.maximum}`);
      }
    }

    if (Array.isArray(value)) {
      if (currentSchema.minItems !== undefined && value.length < currentSchema.minItems) {
        add(path, `array has fewer than ${currentSchema.minItems} items`);
      }
      if (currentSchema.maxItems !== undefined && value.length > currentSchema.maxItems) {
        add(path, `array has more than ${currentSchema.maxItems} items`);
      }
      if (currentSchema.uniqueItems) {
        for (let left = 0; left < value.length; left += 1) {
          for (let right = left + 1; right < value.length; right += 1) {
            if (isDeepStrictEqual(value[left], value[right])) {
              add(path, `array items ${left} and ${right} are equal`);
              left = value.length;
              break;
            }
          }
        }
      }
      if (currentSchema.items !== undefined) {
        value.forEach((item, index) => visit(item, currentSchema.items, `${path}/${index}`));
      }
    }

    if (value && typeof value === "object" && !Array.isArray(value)) {
      const properties = currentSchema.properties ?? {};
      for (const required of currentSchema.required ?? []) {
        if (!Object.hasOwn(value, required)) add(path, `missing required property ${required}`);
      }
      for (const [key, item] of Object.entries(value)) {
        const itemPath = `${path}/${escapePointer(key)}`;
        if (Object.hasOwn(properties, key)) {
          visit(item, properties[key], itemPath);
        } else if (currentSchema.additionalProperties === false) {
          add(itemPath, "additional property is not allowed");
        } else if (
          currentSchema.additionalProperties &&
          typeof currentSchema.additionalProperties === "object"
        ) {
          visit(item, currentSchema.additionalProperties, itemPath);
        }
      }
    }
  }

  visit(instance, schema, "$");
  return errors;
}
