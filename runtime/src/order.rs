//! Where one value is written relative to another.
//!
//! A `Set` and a `Map` are written in ascending order of what their members are written as, so that
//! one collection is one document however it was built. What that order is belongs to Souther and
//! not to this backend: it is `souther.runtime.Representations`, and this is that order over the
//! values this runtime holds rather than over the forms a JVM encoder makes of them.
//!
//! Null first, then false, true, numbers, strings, arrays and objects. Numbers by the amount and
//! then by the way it is written; strings by scalar value, which is the language's order on text and
//! 199x-notation's; arrays element by element with the shorter first; objects as their members read
//! in key order.

use crate::decimal;
use crate::descriptor::{
    self, KIND_BOOL, KIND_DATE, KIND_DATE_TIME, KIND_DECIMAL, KIND_ENUMERATION, KIND_INSTANT,
    KIND_INT, KIND_NEWTYPE,
    KIND_LIST, KIND_MAP, KIND_OPTION, KIND_PRODUCT, KIND_RATIONAL, KIND_SET, KIND_STRING, KIND_SUM,
    KIND_TIME, KIND_TUPLE, KIND_UNIT,
};
use crate::notation;
use crate::rational;
use crate::temporal;
use crate::value;
use crate::{abort, REASON_BACKEND_INVARIANT_BROKEN};

const RANK_NULL: i32 = 0;
const RANK_FALSE: i32 = 1;
const RANK_TRUE: i32 = 2;
const RANK_NUMBER: i32 = 3;
const RANK_STRING: i32 = 4;
const RANK_ARRAY: i32 = 5;
const RANK_OBJECT: i32 = 6;

/// Where a value stands relative to another of its type.
///
/// Not the same question as where it is written. A set of alternatives places its own in the order
/// the declaration writes them, and what each is written as is its name — so a set of them is
/// written in one order and sorted in another, and one unit may be a case of two sets that place
/// it differently. Everything else answers both questions alike.
pub unsafe fn ranked(left: u32, right: u32, descriptor: u32) -> i32 {
    // Where a name for a value stands is where the value it names stands. Walking into the one
    // field it is laid out with arrives at the same place, and that is why nothing here has ever
    // been wrong — but it is an answer about how the value is held rather than about what it is,
    // and the two part company as soon as `rank` is asked, which says a name is written as an
    // object where what it names is written as a string.
    if descriptor::kind(descriptor) == KIND_NEWTYPE {
        return ranked(
            value::__souther_record_get(left, 0),
            value::__souther_record_get(right, 0),
            descriptor::member(descriptor, 0),
        );
    }
    match descriptor::kind(descriptor) {
        KIND_SUM | KIND_ENUMERATION => {
            let a = case_of(left, descriptor);
            let b = case_of(right, descriptor);
            if a != b {
                return if a < b { -1 } else { 1 };
            }
            if descriptor::kind(descriptor) == KIND_ENUMERATION {
                0
            } else {
                ranked(left, right, descriptor::member(descriptor, a))
            }
        }
        KIND_TUPLE => {
            for i in 0..descriptor::arity(descriptor) {
                let each = ranked(
                    value::__souther_tuple_get(left, i),
                    value::__souther_tuple_get(right, i),
                    descriptor::member(descriptor, i),
                );
                if each != 0 {
                    return each;
                }
            }
            0
        }
        KIND_PRODUCT => {
            for i in 0..descriptor::arity(descriptor) {
                let each = ranked(
                    value::__souther_record_get(left, i),
                    value::__souther_record_get(right, i),
                    descriptor::member(descriptor, i),
                );
                if each != 0 {
                    return each;
                }
            }
            0
        }
        KIND_LIST | KIND_SET => {
            let element = descriptor::member(descriptor, 0);
            let a = value::__souther_list_length(left);
            let b = value::__souther_list_length(right);
            let shorter = if a < b { a } else { b };
            for i in 0..shorter {
                let each = ranked(
                    value::__souther_list_get(left, i),
                    value::__souther_list_get(right, i),
                    element,
                );
                if each != 0 {
                    return each;
                }
            }
            if a < b {
                -1
            } else if a > b {
                1
            } else {
                0
            }
        }
        _ => compare(left, right, descriptor),
    }
}

/// A hash of a value, the same for any two that `ranked` answers nothing between.
///
/// Read off what `ranked` and `compare` compare, part by part, so two values that stand in one place
/// have one hash whatever cells hold them: an amount by how much it is and not by its scale, a
/// moment by when it is and not by how it was spelt, a case by which case it is.
///
/// And off every part they compare, none left out. A hash that leaves a part out is still never
/// two hashes for one value, but every value differing only in that part shares one, and a caller
/// choosing keys can make every key of a map collide: a table looking keys up by hash then walks
/// every key per key, which is the square of how many there are. So a map is hashed by its entries,
/// each key with its value, added up so that the order the entries stand in decides nothing, as
/// the JVM backend's `PersistentHashMap.valueHash` does.
///
/// Every kind a descriptor can name has its own arm. One this does not know ends the call rather
/// than sharing a hash with every other value of its kind.
pub unsafe fn hash_of(cell: u32, descriptor: u32) -> u32 {
    match descriptor::kind(descriptor) {
        KIND_NEWTYPE => hash_of(value::__souther_record_get(cell, 0), descriptor::member(descriptor, 0)),
        KIND_OPTION => match held(cell) {
            0 => mixed(HASH_START, 0),
            inner => mixed(mixed(HASH_START, 1), hash_of(inner, descriptor::member(descriptor, 0))),
        },
        KIND_BOOL => mixed(HASH_START, value::__souther_bool_value(cell)),
        KIND_INT => wide(HASH_START, value::__souther_int_value(cell)),
        KIND_DECIMAL => {
            let (at, length) = decimal::written(decimal::canonical(cell));
            bytes(HASH_START, at, length)
        }
        // Its parts, which are one value's own, so two cells holding one value hash alike. Read
        // off the cell and not worked out, so no arithmetic comes with it.
        KIND_RATIONAL => {
            let (at, length) = rational::parts(cell);
            bytes(HASH_START, at, length)
        }
        KIND_STRING => bytes(
            HASH_START,
            value::__souther_string_bytes(cell),
            value::__souther_string_length(cell),
        ),
        KIND_DATE | KIND_TIME | KIND_DATE_TIME => wide(HASH_START, temporal::moment(cell)),
        KIND_INSTANT => mixed(
            wide(HASH_START, temporal::moment_second(cell)),
            temporal::moment_nano(cell) as u32,
        ),
        // Which case it is and what it carries, asked of the value as `compare` asks it, so that
        // one value has one hash whatever type the place holding it was written as: a unit, a
        // shape, a set of units and a sum listing them hash a value alike, and a set not listing
        // it still hashes it as itself.
        KIND_UNIT | KIND_PRODUCT | KIND_ENUMERATION | KIND_SUM => {
            let case = case_held(cell);
            let mut hash = mixed(HASH_START, case);
            if descriptor::kind(case) == KIND_PRODUCT {
                for i in 0..descriptor::arity(case) {
                    hash = mixed(hash, hash_of(value::__souther_record_get(cell, i), descriptor::member(case, i)));
                }
            }
            hash
        }
        KIND_TUPLE => {
            let mut hash = HASH_START;
            for i in 0..descriptor::arity(descriptor) {
                hash = mixed(hash, hash_of(value::__souther_tuple_get(cell, i), descriptor::member(descriptor, i)));
            }
            hash
        }
        KIND_LIST | KIND_SET => {
            let element = descriptor::member(descriptor, 0);
            let held = value::__souther_list_length(cell);
            let mut hash = mixed(HASH_START, held);
            for i in 0..held {
                hash = mixed(hash, hash_of(value::__souther_list_get(cell, i), element));
            }
            hash
        }
        KIND_MAP => {
            let keys = descriptor::member(descriptor, 0);
            let values = descriptor::member(descriptor, 1);
            let held = value::__souther_map_length(cell);
            let mut entries: u32 = 0;
            for i in 0..held {
                entries = entries.wrapping_add(mixed(
                    hash_of(value::__souther_map_key(cell, i), keys),
                    hash_of(value::__souther_map_value(cell, i), values),
                ));
            }
            mixed(mixed(HASH_START, held), entries)
        }
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, cell as u64),
    }
}

const HASH_START: u32 = 0x811c_9dc5;

/// One more word into a hash.
fn mixed(hash: u32, word: u32) -> u32 {
    let mut out = hash;
    for byte in word.to_le_bytes() {
        out = (out ^ u32::from(byte)).wrapping_mul(0x0100_0193);
    }
    out
}

fn wide(hash: u32, word: i64) -> u32 {
    mixed(mixed(hash, word as u32), (word >> 32) as u32)
}

unsafe fn bytes(hash: u32, at: u32, length: u32) -> u32 {
    let mut out = mixed(hash, length);
    for i in 0..length {
        out = (out ^ u32::from(core::ptr::read((at + i) as usize as *const u8))).wrapping_mul(0x0100_0193);
    }
    out
}

/// Where a value is written relative to another of the same type.
pub unsafe fn compare(left: u32, right: u32, descriptor: u32) -> i32 {
    // What an optional is written as is what it holds, or nothing at all — so what it is compared
    // by is that, and the cell holding it is not a value anybody wrote. Opened here rather than
    // where the rank is asked, because the rank is asked of what is written and everything below
    // is then handed the value the rank was about.
    if descriptor::kind(descriptor) == KIND_OPTION {
        let (a, b) = (held(left), held(right));
        if a == 0 || b == 0 {
            return if a == b {
                0
            } else if a == 0 {
                -1
            } else {
                1
            };
        }
        return compare(a, b, descriptor::member(descriptor, 0));
    }
    if descriptor::kind(descriptor) == KIND_NEWTYPE {
        return compare(
            value::__souther_record_get(left, 0),
            value::__souther_record_get(right, 0),
            descriptor::member(descriptor, 0),
        );
    }
    if declares_a_value(descriptor::kind(descriptor)) {
        return declared(left, right);
    }
    // A tuple has no written form, so no place among written values; it stands where its parts
    // do, in order. Laid out as a tuple and not as a list, so it is not read as one.
    if descriptor::kind(descriptor) == KIND_TUPLE {
        for i in 0..descriptor::arity(descriptor) {
            let each = compare(
                value::__souther_tuple_get(left, i),
                value::__souther_tuple_get(right, i),
                descriptor::member(descriptor, i),
            );
            if each != 0 {
                return each;
            }
        }
        return 0;
    }
    let a = rank(left, descriptor);
    let b = rank(right, descriptor);
    if a != b {
        return if a < b { -1 } else { 1 };
    }
    match a {
        RANK_NULL | RANK_FALSE | RANK_TRUE => 0,
        RANK_NUMBER => {
            if descriptor::kind(descriptor) == KIND_DECIMAL {
                // How much it is, and nothing about how it was written. Two amounts differing only
                // in scale are one amount, and a boundary writes them as one thing, so a
                // collection holding one of them cannot be told to hold the other beside it.
                return decimal::compare(left, right);
            }
            if descriptor::kind(descriptor) == KIND_RATIONAL {
                // By exact value. Nothing writes one, so this is the order a set and a sort of them
                // stand in, which is the order the language states for them.
                return descriptor::ordered_exactly(descriptor, left, right);
            }
            let x = value::__souther_int_value(left);
            let y = value::__souther_int_value(right);
            if x < y {
                -1
            } else if x > y {
                1
            } else {
                0
            }
        }
        RANK_STRING => match descriptor::kind(descriptor) {
            KIND_INSTANT => {
                let (a, b) = (temporal::moment_second(left), temporal::moment_second(right));
                if a != b {
                    return if a < b { -1 } else { 1 };
                }
                let (a, b) = (temporal::moment_nano(left), temporal::moment_nano(right));
                if a < b {
                    -1
                } else if a > b {
                    1
                } else {
                    0
                }
            }
            KIND_DATE | KIND_TIME | KIND_DATE_TIME => {
                let a = temporal::moment(left);
                let b = temporal::moment(right);
                if a < b {
                    -1
                } else if a > b {
                    1
                } else {
                    0
                }
            }
            _ => text(left, right),
        },
        RANK_ARRAY => elements(left, right, descriptor),
        _ => members(left, right, descriptor),
    }
}

/// What form a value of a type is written as, which is the first thing the order asks.
unsafe fn rank(cell: u32, descriptor: u32) -> i32 {
    match descriptor::kind(descriptor) {
        KIND_BOOL => {
            if value::__souther_bool_value(cell) != 0 {
                RANK_TRUE
            } else {
                RANK_FALSE
            }
        }
        KIND_INT | KIND_DECIMAL | KIND_RATIONAL => RANK_NUMBER,
        // A day and a time of day cross as the text a calendar and a clock write them as.
        KIND_STRING | KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => RANK_STRING,
        KIND_LIST | KIND_SET => RANK_ARRAY,
        KIND_OPTION => {
            if held(cell) == 0 {
                RANK_NULL
            } else {
                rank(held(cell), descriptor::member(descriptor, 0))
            }
        }
        // An alternative that carries nothing is written as its name, which is a string.
        KIND_ENUMERATION => RANK_STRING,
        KIND_TUPLE => RANK_ARRAY,
        KIND_UNIT | KIND_PRODUCT | KIND_SUM | KIND_MAP => RANK_OBJECT,
        _ => RANK_OBJECT,
    }
}

/// What an option holds, or nothing.
unsafe fn held(cell: u32) -> u32 {
    if core::ptr::read_unaligned(cell as usize as *const u32) == value::TAG_NONE {
        0
    } else {
        core::ptr::read_unaligned((cell as usize + 8) as *const u32)
    }
}

/// Two runs of text by scalar value, which is the language's order on text.
pub unsafe fn compare_runs(at: u32, length: u32, other: u32, other_length: u32) -> i32 {
    notation199x::compare(notation::str_at(at, length), notation::str_at(other, other_length)) as i32
}

unsafe fn text(left: u32, right: u32) -> i32 {
    notation199x::compare(notation::str_of(left), notation::str_of(right)) as i32
}

unsafe fn elements(left: u32, right: u32, descriptor: u32) -> i32 {
    let element = descriptor::member(descriptor, 0);
    let a = value::__souther_list_length(left);
    let b = value::__souther_list_length(right);
    let shorter = if a < b { a } else { b };
    for i in 0..shorter {
        let each = compare(
            value::__souther_list_get(left, i),
            value::__souther_list_get(right, i),
            element,
        );
        if each != 0 {
            return each;
        }
    }
    if a < b {
        -1
    } else if a > b {
        1
    } else {
        0
    }
}

/// Two values written as objects.
///
/// Of one type, so the keys are the same and in the same order, and only what is under them
/// decides. A `Unit` writes no members at all and two of them are one document.
unsafe fn members(left: u32, right: u32, descriptor: u32) -> i32 {
    if descriptor::kind(descriptor) == KIND_MAP {
        return entries(left, right, descriptor);
    }
    abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, left as u64, right as u64)
}

/// Whether a kind describes a value a model declared — a unit, a shape, or a set of alternatives
/// of them — which carries in its cell the descriptor of what it was made as.
fn declares_a_value(kind: u32) -> bool {
    matches!(kind, KIND_UNIT | KIND_PRODUCT | KIND_ENUMERATION | KIND_SUM)
}

/// Two values a model declared, where they are written, each read as what it was made as.
///
/// Not as the descriptor that reached here, which is whatever type the place holding them was
/// written as: a set made where its members were one case, or a union of a few, holds that, and
/// keeps it when it is later held as the whole sum, so a value put in or looked for there may be a
/// case it does not describe — read by it, a unit would be taken for any other unit and a shape's
/// fields read off a cell that has none. The cell says which case it is, and that case's own
/// descriptor reads it. Which case decides first, by the name it is written as — a set of units is
/// written as their names, and a sum as an object whose tag comes first — and then what the case
/// carries, field by field.
unsafe fn declared(left: u32, right: u32) -> i32 {
    let by_case = cases(left, right);
    if by_case != 0 {
        return by_case;
    }
    let own = case_held(left);
    if descriptor::kind(own) != KIND_PRODUCT {
        return 0;
    }
    for i in 0..descriptor::arity(own) {
        let each = compare(
            value::__souther_record_get(left, i),
            value::__souther_record_get(right, i),
            descriptor::member(own, i),
        );
        if each != 0 {
            return each;
        }
    }
    0
}

/// Two maps: their entries read in key order, key against key and then value against value, with
/// the one holding fewer first where everything they share agrees.
unsafe fn entries(left: u32, right: u32, descriptor: u32) -> i32 {
    let keys = descriptor::member(descriptor, 0);
    let values = descriptor::member(descriptor, 1);
    let a = value::__souther_map_length(left);
    let b = value::__souther_map_length(right);
    let shorter = if a < b { a } else { b };
    for i in 0..shorter {
        // Where a key stands among a map's keys, which is the same question a map's own order asks
        // of it and is answered in the same place.
        let by_key = value::key_order(
            value::__souther_map_key(left, i),
            value::__souther_map_key(right, i),
            keys,
        );
        if by_key != 0 {
            return by_key;
        }
        let by_value = compare(
            value::__souther_map_value(left, i),
            value::__souther_map_value(right, i),
            values,
        );
        if by_value != 0 {
            return by_value;
        }
    }
    if a < b {
        -1
    } else if a > b {
        1
    } else {
        0
    }
}

/// Which case a value is, read off the value: the descriptor of the case its cell holds.
///
/// Asked of the value and not of the set of alternatives it is met as, because that set is
/// whatever descriptor reached this — a collection's own, written where it was made, may be a
/// union narrower than the sum a value later put in it was made as, and does not list that value
/// at all. One case is one descriptor wherever it is listed, so this is the same answer for every
/// set that lists it.
pub unsafe fn case_held(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + 4) as *const u32)
}

/// Two cases where they are written: by the names they are written as, and two cases that share a
/// name — one from each of two sums — by which they are, so that only one case is one value.
/// Neither is asked of a set of alternatives, so a case one does not list stands in its place too.
unsafe fn cases(left: u32, right: u32) -> i32 {
    let (a, b) = (case_held(left), case_held(right));
    if a == b {
        return 0;
    }
    let (first, first_length) = descriptor::own_name(a);
    let (second, second_length) = descriptor::own_name(b);
    let by_name = compare_runs(first, first_length, second, second_length);
    if by_name != 0 {
        by_name
    } else if a < b {
        -1
    } else {
        1
    }
}

/// Where a case stands among a set's alternatives, for the order that set declares.
///
/// Only the language's order asks this, and it asks it of the set the checker settled to order the
/// value by, which lists every case such a value can be. A case it does not list is the compiler
/// handing this a set the value is not one of, and it ends the call: taken for the first case, it
/// would be equal to that one, which is a wrong answer and not a failure.
unsafe fn case_of(cell: u32, descriptor: u32) -> u32 {
    let own = case_held(cell);
    for i in 0..descriptor::arity(descriptor) {
        if descriptor::member(descriptor, i) == own {
            return i;
        }
    }
    abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, own as u64, cell as u64)
}
