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
//!      u32 where the table of what its clauses are reported as is, u32 nothing: a newtype has no
//!      keys
//!
//! kind PRODUCT
//! +4  u32 how many fields
//! +8  per field: u32 where its name is, u32 how long, u32 the field's own descriptor
//! then u32 where the type's own name is, u32 how long, u32 the slot of what checks it,
//!      u32 where the table of what its clauses are reported as is, u32 where the index of the
//!      fields' names is (see below)
//!
//! kind SUM / ENUMERATION
//! +4  u32 how many cases
//! +8  per case: u32 where its tag is, u32 how long, u32 the case's own descriptor
//! then, of a SUM, u32 where the key the tag stands under is, u32 how long, u32 where the key a
//!      case carried as itself stands under is, u32 how long — the checker's, nothing for a set
//!      only a body holds
//! then u32 where the index of its cases is, u32 where the index of their tags is (see below)
//!
//! kind LIST / OPTION
//! +4  u32 one
//! +8  u32 nothing, u32 nothing, u32 the descriptor of what it holds
//! ```
//!
//! A list and an option are written with one member so that everything with members is read the
//! same way. What their member is called is nothing, because nothing names it.
//!
//! Before every descriptor, sixteen bytes say what reads, writes and orders a value of its type,
//! and where it lies when it crosses typed:
//!
//! ```text
//! -16 u32 the slot of its reader       (`__souther_read_*`)
//! -12 u32 the slot of its writer       (`__souther_write_*`)
//! -8  u32 the slot of its order        (`__souther_order_*`)
//! -4  u32 where its typed layout is    (`crate::typed`)
//! ```
//!
//! Each is filled only where the program asks it of the type, and is nothing otherwise. Which
//! function answers for a kind is the compiler's to say, because it is the compiler that knows
//! which kinds a program reads, writes and orders: chosen here by the kind, every reader, writer
//! and order would be reached from any one of them, and a module reading an `Int` would carry what
//! normalizes text and what does exact arithmetic with it (issue #44).
//!
//! A unit carries its descriptor in its cell and a product carries its own, so which case of a sum
//! a value is can be asked of the value: the case whose descriptor the cell holds. Both name
//! themselves after their fields, so what a case is called is asked of the value too, and not of
//! a set of alternatives it is met as — which may be a union narrower than what it was made as,
//! not listing it at all.

//! The index of a set's cases, and of any other set of leaves, is a table the compiler writes once:
//!
//! ```text
//! +0  u32 mask, one less than how many slots there are, a power of two
//! +4  per slot: u32 the descriptor of a leaf, nothing where the slot is empty, u32 what it maps to
//! ```
//!
//! A leaf is looked up by `index_find`: the slot its descriptor hashes to, then the next one, until
//! the slot holds the descriptor or is empty. There are at least twice as many slots as leaves, so
//! one is always empty. The hash is `((descriptor * 0x9E3779B1) >> 16) & mask` over 32 bits, which
//! the compiler computes the same way when it lays the table out (`CaseIndex`). A set's own index
//! maps a leaf to its place among the cases; the table a `match` or a composition asks of a value
//! maps a leaf to the arm that takes it, or to one where it is accepted.

//! The index of names, which a reader tells the field a key names and the case a tag names by, is
//! another table the compiler writes once (`NameIndex`):
//!
//! ```text
//! +0  u32 mask, one less than how many slots there are, a power of two
//! +4  per slot: u32 where a name is written, nothing where the slot is empty, u32 how long, u32
//!     its place
//! ```
//!
//! Found by `name_find` the way a leaf is, from the hash (FNV-1a over the bytes, its high half
//! folded into its low) and then by the text itself, so one lookup costs what comparing one name
//! does and not what comparing each of them does.

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

/// Where the index of a set of alternatives' cases is: the table that maps the descriptor of a
/// leaf to its place among the cases.
pub unsafe fn case_index(descriptor: u32) -> u32 {
    let after = descriptor as usize + 8 + 12 * arity(descriptor) as usize;
    read(if kind(descriptor) == KIND_SUM { after + 16 } else { after })
}

/// Where the index of a set of alternatives' tags is: the table that maps a case's name to its place.
pub unsafe fn tag_index(descriptor: u32) -> u32 {
    let after = descriptor as usize + 8 + 12 * arity(descriptor) as usize;
    read(if kind(descriptor) == KIND_SUM { after + 20 } else { after + 4 })
}

/// Where the index of a product's field names is: the table that maps a field's name to its place.
pub unsafe fn field_index(descriptor: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * arity(descriptor) as usize + 16)
}

/// What a table written as `NameIndex` maps a name to, or nothing where it holds none.
pub unsafe fn name_find(table: u32, name: u32, length: u32) -> Option<u32> {
    let mask = read(table as usize);
    let mut hash = 0x811C_9DC5u32;
    for i in 0..length as usize {
        hash = (hash ^ core::ptr::read((name as usize + i) as *const u8) as u32).wrapping_mul(0x0100_0193);
    }
    let mut slot = (hash ^ (hash >> 16)) & mask;
    loop {
        let at = table as usize + 4 + 12 * slot as usize;
        let held = read(at);
        if held == 0 {
            return None;
        }
        if read(at + 4) == length && same_text(held, name, length) {
            return Some(read(at + 8));
        }
        slot = (slot + 1) & mask;
    }
}

unsafe fn same_text(left: u32, right: u32, length: u32) -> bool {
    (0..length as usize).all(|i| {
        core::ptr::read((left as usize + i) as *const u8) == core::ptr::read((right as usize + i) as *const u8)
    })
}

/// What a table written as `CaseIndex` maps a leaf's descriptor to, or nothing where it holds none.
pub unsafe fn index_find(table: u32, leaf: u32) -> Option<u32> {
    let mask = read(table as usize);
    let mut slot = (leaf.wrapping_mul(0x9E37_79B1) >> 16) & mask;
    loop {
        let at = table as usize + 4 + 8 * slot as usize;
        let held = read(at);
        if held == leaf {
            return Some(read(at + 4));
        }
        if held == 0 {
            return None;
        }
        slot = (slot + 1) & mask;
    }
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

/// The slot of what reads a value of the type from a document, or nothing where none is asked.
pub unsafe fn reader(descriptor: u32) -> u32 {
    read(descriptor as usize - 16)
}

/// The slot of what writes a value of the type into a document, or nothing where none is asked.
pub unsafe fn writer(descriptor: u32) -> u32 {
    read(descriptor as usize - 12)
}

/// The slot of what places a value of the type among a set's members, or nothing where none is
/// asked.
pub unsafe fn orderer(descriptor: u32) -> u32 {
    read(descriptor as usize - 8)
}

/// Where two exact quotients stand, by the function the slot their descriptor holds names.
pub unsafe fn ordered_exactly(descriptor: u32, left: u32, right: u32) -> i32 {
    let order: extern "C" fn(u32, u32) -> i32 =
        core::mem::transmute(read(descriptor as usize + 4) as usize);
    order(left, right)
}

/// Where a value of the type lies when it crosses typed, as the compiler laid it out by the
/// Canonical ABI (`crate::typed`), or nothing where no typed boundary carries the type.
pub unsafe fn layout(descriptor: u32) -> u32 {
    read(descriptor as usize - 4)
}

/// The table slot of what checks a product's invariants, or zero where it has none.
pub unsafe fn invariant(descriptor: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * arity(descriptor) as usize + 8)
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

/// Every kind a case can be is named, and one that is not ends the call: a kind added later is a
/// question this has not answered, not one it answers as the last of the three.
pub unsafe fn carried(case: u32) -> Carried {
    match kind(case) {
        KIND_UNIT => Carried::Nothing,
        KIND_PRODUCT => Carried::Fields,
        KIND_NEWTYPE | KIND_INT | KIND_BOOL | KIND_STRING | KIND_DECIMAL | KIND_RATIONAL
        | KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => Carried::Itself,
        other => crate::abort(crate::REASON_BACKEND_INVARIANT_BROKEN, case, other as u64, 0),
    }
}

/// Where the program's descriptors of the primitives are: one word per primitive, in the order
/// `primitive` reads them, nothing where the program holds no descriptor of that primitive.
///
/// The program's, and not this runtime's own. What a primitive value is is asked of the value and
/// not of a type (`order::identity`), and the descriptor answering it is then asked what orders it.
/// A descriptor this runtime held would have to name every primitive's order itself, and every one
/// of them would be reached from any.
static mut PRIMITIVES: u32 = 0;

/// Takes where the program's descriptors of the primitives are, at instantiation.
pub unsafe fn held_primitives(at: u32) {
    PRIMITIVES = at;
}

/// The program's descriptor of a primitive kind, for a value of it asked what it is, or nothing
/// where the program holds none of it.
///
/// A program holding a value of a primitive holds the primitive's descriptor, so a value met of
/// one the program holds none of is this compiler and this runtime disagreeing about what the
/// program holds. Whether that ends the call is the caller's to say: a leaf asked of a set it is
/// not in is an answer, and a value ordered among a set's alternatives is the end of the call.
pub unsafe fn try_primitive(kind: u32) -> Option<u32> {
    let at = match kind {
        KIND_INT => 0,
        KIND_BOOL => 1,
        KIND_STRING => 2,
        KIND_DECIMAL => 3,
        KIND_RATIONAL => 4,
        KIND_DATE => 5,
        KIND_TIME => 6,
        KIND_DATE_TIME => 7,
        KIND_INSTANT => 8,
        _ => return None,
    };
    let held = if PRIMITIVES == 0 { 0 } else { read(PRIMITIVES as usize + 4 * at) };
    if held == 0 { None } else { Some(held) }
}

/// What a type is called, for telling one case from another where they are written: a unit's,
/// a shape's or a newtype's own name, and a primitive's, which is the language's name for it.
pub unsafe fn called(descriptor: u32) -> (u32, u32) {
    let spelt: &'static [u8] = match kind(descriptor) {
        KIND_UNIT | KIND_PRODUCT | KIND_NEWTYPE => return own_name(descriptor),
        KIND_INT => b"Int",
        KIND_BOOL => b"Bool",
        KIND_STRING => b"String",
        KIND_DECIMAL => b"Decimal",
        KIND_RATIONAL => b"Rational",
        KIND_DATE => b"Date",
        KIND_TIME => b"Time",
        KIND_DATE_TIME => b"DateTime",
        KIND_INSTANT => b"Instant",
        other => crate::abort(crate::REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, 0),
    };
    (spelt.as_ptr() as u32, spelt.len() as u32)
}

/// Where the table of what a product's clauses are reported as is (`crate::clauses`).
pub unsafe fn clauses(descriptor: u32) -> u32 {
    read(descriptor as usize + 8 + 12 * arity(descriptor) as usize + 12)
}

unsafe fn read(at: usize) -> u32 {
    core::ptr::read_unaligned(at as *const u32)
}
