// What `souther:surface` says, version 4, as types.
//
// The compiler's `TheSurfaceChangesOnlyWithItsVersionTest` holds what a surface of a version says
// to a file named for it; these are what that file says, for a reader written in TypeScript.

/** A scalar, by the name the surface gives it. */
export type Scalar =
  | "string" | "int" | "bool" | "decimal" | "date" | "time" | "datetime" | "instant";

/** A type a value is written as. */
export type Shape =
  | { readonly is: "scalar"; readonly scalar: Scalar }
  | { readonly is: "declared"; readonly module: string; readonly name: string }
  | { readonly is: "list"; readonly of: Shape }
  | { readonly is: "set"; readonly of: Shape }
  | { readonly is: "map"; readonly key: Shape; readonly value: Shape }
  | { readonly is: "option"; readonly of: Shape }
  | {
    readonly is: "union";
    readonly members: readonly Shape[];
    readonly crossing: { readonly cases: readonly Shape[]; readonly form: Form };
  };

/** How a set of alternatives travels. */
export type Form =
  | { readonly is: "enumeration" }
  | { readonly is: "discriminated"; readonly tag: string; readonly contents: string };

export interface Behavior {
  readonly name: string;
  /** What a caller calls it through, which only a behavior its module publishes has. */
  readonly export?: string;
  readonly published: boolean;
  readonly implementation: "here" | "injected" | "unwritten" | "elsewhere";
  readonly reachOut?: number;
  readonly parameters: readonly { readonly name: string | null; readonly type: Shape }[];
  readonly answers: Shape;
}

interface Named {
  readonly module: string;
  readonly name: string;
  readonly by: "module" | "path" | "language";
  readonly published: boolean | null;
  readonly decode?: number;
}

export type Declaration = Named & (
  | {
    readonly is: "product";
    readonly fields: readonly { readonly name: string; readonly type: Shape }[];
    readonly rules: readonly { readonly name: string | null }[];
  }
  | { readonly is: "newtype"; readonly wraps: Shape; readonly rules: readonly { readonly name: string | null }[] }
  | { readonly is: "sum"; readonly cases: readonly Shape[]; readonly form: Form }
  | { readonly is: "unit" }
);

export interface Surface {
  readonly version: number;
  readonly modules: readonly { readonly name: string; readonly behaviors: readonly Behavior[] }[];
  readonly declarations: readonly Declaration[];
}

/** How a primitive standing as a case of an alternative is tagged: by its name in the language. */
const PRIMITIVE_TAGS: Readonly<Record<Scalar, string>> = {
  string: "String", int: "Int", bool: "Bool", decimal: "Decimal",
  date: "Date", time: "Time", datetime: "DateTime", instant: "Instant",
};

/** What a case of an alternative is tagged with where it travels: its declaration's name, or its primitive's. */
export function tagOf(shape: Shape): string {
  switch (shape.is) {
    case "declared":
      return shape.name;
    case "scalar":
      return PRIMITIVE_TAGS[shape.scalar];
    default:
      throw new Error(`a case is a declaration or a primitive, and this is ${shape.is}`);
  }
}

/** A declaration's name with its module's, which is what tells it from every other. */
export function keyOf(declaration: { readonly module: string; readonly name: string }): string {
  return `${declaration.module}.${declaration.name}`;
}
