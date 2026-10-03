//! What a declared type is, written once in static memory.
//!
//! Reading and writing are driven by the declaration, not by the value and not by the document. A
//! place declared `Int` holding a string is bad input; a value of a shape written where a sum was
//! declared carries the tag that says which case it is, and the same value written where the case
//! itself was declared does not. Neither of those is decidable from the value alone, so what a
//! reader and a writer are handed is the type the place was declared to hold.
//!
//! Which makes the descriptor the whole of what the emitter has to say about a type. It places one
//! per declared type and passes its address; nothing else about the shape of a value crosses from
//! the Java side into a generated body.
//!
//! ```text
//! +0  u32 kind
//!
//! kind INT / BOOL / STRING   nothing more
//!
//! kind UNIT
//! +4  u32 nought, for the fields it does not have
//! +8  u32 where its own name is, u32 how long
//!
//! kind NEWTYPE
//! +4  u32 one
//! +8  u32 where the field's name is, u32 how long, u32 the field's own descriptor
//! then u32 where the type's own name is, u32 how long, u32 the slot of what checks it,
//!      u32 where the table of what its clauses are reported as is
//!
//! kind PRODUCT
//! +4  u32 how many fields
//! +8  per field: u32 where its name is, u32 how long, u32 the field's own descriptor
//! then u32 where the type's own name is, u32 how long, u32 the slot of what checks it,
//!      u32 where the table of what its clauses are reported as is
//!
//! kind SUM / ENUMERATION
//! +4  u32 how many cases
//! +8  per case: u32 where its tag is, u32 how long, u32 the case's own descriptor
//! then, of an ENUMERATION, u32 where the set's own name is, u32 how long (nothing where nobody
//!      named it)
//! then, of a SUM, u32 where the key the tag stands under is, u32 how long, u32 where the key a
//!      case carried as itself stands under is, u32 how long — the checker's, nothing for a set
//!      only a body holds
//!
//! kind LIST / OPTION
//! +4  u32 one
//! +8  u32 nothing, u32 nothing, u32 the descriptor of what it holds
//! ```
//!
//! A list and an option are written with one member so that everything with members is read the
//! same way. What their member is called is nothing, because nothing names it.
//!
//! A unit carries its descriptor in its cell and a product carries its own, so which case of a sum
//! a value is can be asked of the value: the case whose descriptor the cell holds. Both name
//! themselves after their fields, so what a case is called is asked of the value too, and not of
//! a set of alternatives it is met as — which may be a union narrower than what it was made as,
//! not listing it at all.

/// An `Int`.
pub const KIND_INT: u32 = 0;
/// A `Bool`.
pub const KIND_BOOL: u32 = 1;
/// A `String`.
pub const KIND_STRING: u32 = 2;
/// A `Decimal`.
pub const KIND_DECIMAL: u32 = 12;
/// A `Date`.
pub const KIND_DATE: u32 = 13;
/// A `Time`.
pub const KIND_TIME: u32 = 14;
/// A `DateTime`.
pub const KIND_DATE_TIME: u32 = 15;

/// A moment on the timeline, which is not a calendar reading and has no zone until one is named.
pub const KIND_INSTANT: u32 = 16;

/// A name for a value of another type, with rules of its own.
///
/// Laid out as a product of one field, because that is what a value of one is made of — the field,
/// the binding a clause reads it through, and the slot of what checks it. What differs is only how
/// it crosses: a value of it is written as the type it is a name for is written, so what reads and
/// writes one asks the field's own descriptor and nothing here says `{"value": ...}`.
pub const KIND_NEWTYPE: u32 = 17;
/// An exact quotient. It has no external form, so nothing reads one from a document or writes one
/// into one: a descriptor of one is what a collection holding them, or a comparison of them, asks.
///
/// Not 18, which the compiler gives the elements of a list nothing said the type of, so that no
/// reader here handles it.
///
/// ```text
/// +4  u32 the slot of what orders two of them
/// ```
///
/// Ordered through a slot and not by a call, because what orders two of them is exact arithmetic
/// and the order of every other kind is reached from the same function: a call would carry that
/// arithmetic into every module that compares anything. A descriptor of one is written only where a
/// program holds one, so only that program's module holds what the slot names.
pub const KIND_RATIONAL: u32 = 19;
/// A type with one value.
pub const KIND_UNIT: u32 = 3;
/// A type written as fields.
pub const KIND_PRODUCT: u32 = 4;
/// A type written as cases, at least one of which carries something of its own.
pub const KIND_SUM: u32 = 5;
/// A set of alternatives that each carry nothing but which one they are, so the value is the tag.
pub const KIND_ENUMERATION: u32 = 10;
/// Values written together and read back by their places, which nothing names.
pub const KIND_TUPLE: u32 = 11;
/// A list, whose one member is what its elements are.
pub const KIND_LIST: u32 = 6;
/// An option, whose one member is what it holds when it holds one.
pub const KIND_OPTION: u32 = 7;
/// A set, whose one member is what its elements are.
pub const KIND_SET: u32 = 8;
/// A map, whose two members are what its keys are and what its values are.
pub const KIND_MAP: u32 = 9;

/// What kind of type a descriptor describes.
pub unsafe fn kind(descriptor: u32) -> u32 {
    read(descriptor as usize)
}

/// How many fields a product has, or how many cases a sum has.
pub unsafe fn arity(descriptor: u32) -> u32 {
    read(descriptor as usize + 4)
}

/// Where a field's name, or a case's tag, is written, and how long it is.
pub unsafe fn name(descriptor: u32, index: u32) -> (u32, u32) {
    let at = descriptor as usize + 8 + 12 * index as usize;
    (read(at), read(at + 4))
}

/// The descriptor of a field's type, or of a case.
pub unsafe fn member(descriptor: u32, index: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * index as usize + 8)
}

/// Where a product's or a unit's own name is, and how long it is: for an issue that names the
/// type, and for what a case is called whichever set of alternatives it is met as.
pub unsafe fn own_name(descriptor: u32) -> (u32, u32) {
    let at = descriptor as usize + 8 + 12 * arity(descriptor) as usize;
    (read(at), read(at + 4))
}

/// Where two exact quotients stand, by the function the slot their descriptor holds names.
pub unsafe fn ordered_exactly(descriptor: u32, left: u32, right: u32) -> i32 {
    let order: extern "C" fn(u32, u32) -> i32 =
        core::mem::transmute(read(descriptor as usize + 4) as usize);
    order(left, right)
}

/// The table slot of what checks a product's invariants, or zero where it has none.
pub unsafe fn invariant(descriptor: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * arity(descriptor) as usize + 8)
}

/// Where an enumeration's own name is, and how long it is, for an issue that names the set a name
/// is not one of. Nothing for a set nobody named.
pub unsafe fn enumeration_name(descriptor: u32) -> (u32, u32) {
    let at = descriptor as usize + 8 + 12 * arity(descriptor) as usize;
    (read(at), read(at + 4))
}

/// The key a sum's tag stands under, as the checker settled the sum's form. Nothing — no address —
/// for a set only a body holds, which nothing reads or writes.
pub unsafe fn tag_key(descriptor: u32) -> (u32, u32) {
    let at = descriptor as usize + 8 + 12 * arity(descriptor) as usize;
    (read(at), read(at + 4))
}

/// The key a case of a sum that is carried as itself stands under, beside the tag.
pub unsafe fn contents_key(descriptor: u32) -> (u32, u32) {
    let at = descriptor as usize + 8 + 12 * arity(descriptor) as usize + 8;
    (read(at), read(at + 4))
}

/// What a case of a set of alternatives carries, by what its own descriptor is.
///
/// The checker's three (`CaseShape`), decided the way it decides them: a unit carries nothing but
/// which case it is, a shape lays its fields beside the tag, and anything else — a newtype, a
/// primitive member of an answer — is carried as itself, its own form unchanged under a key of its
/// own. Every place that reads, writes, compares or hashes what a case carries asks this, so a
/// kind is never one of the three in one place and another of them somewhere else.
pub enum Carried {
    Nothing,
    Fields,
    Itself,
}

pub unsafe fn carried(case: u32) -> Carried {
    match kind(case) {
        KIND_UNIT => Carried::Nothing,
        KIND_PRODUCT => Carried::Fields,
        _ => Carried::Itself,
    }
}

/// Where the table of what a product's clauses are reported as is (`crate::clauses`).
pub unsafe fn clauses(descriptor: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * arity(descriptor) as usize + 12)
}

unsafe fn read(at: usize) -> u32 {
    core::ptr::read_unaligned(at as *const u32)
}
