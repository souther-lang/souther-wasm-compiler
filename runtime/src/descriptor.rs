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
//! kind INT / BOOL / STRING / UNIT   nothing more
//!
//! kind PRODUCT
//! +4  u32 how many fields
//! +8  per field: u32 where its name is, u32 how long, u32 the field's own descriptor
//!
//! kind SUM
//! +4  u32 how many cases
//! +8  per case: u32 where its tag is, u32 how long, u32 the case's own descriptor
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
//! a value is can be asked of the value: the case whose descriptor the cell holds.

/// An `Int`.
pub const KIND_INT: u32 = 0;
/// A `Bool`.
pub const KIND_BOOL: u32 = 1;
/// A `String`.
pub const KIND_STRING: u32 = 2;
/// A type with one value.
pub const KIND_UNIT: u32 = 3;
/// A type written as fields.
pub const KIND_PRODUCT: u32 = 4;
/// A type written as cases.
pub const KIND_SUM: u32 = 5;
/// A list, whose one member is what its elements are.
pub const KIND_LIST: u32 = 6;
/// An option, whose one member is what it holds when it holds one.
pub const KIND_OPTION: u32 = 7;
/// A set, whose one member is what its elements are.
pub const KIND_SET: u32 = 8;

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

unsafe fn read(at: usize) -> u32 {
    core::ptr::read_unaligned(at as *const u32)
}
