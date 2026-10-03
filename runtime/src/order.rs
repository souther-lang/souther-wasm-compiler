//! Where one value stands relative to another, which is three questions.
//!
//! The order the language states (`ranked`): what `<`, a sort, a max and a min place values by,
//! asked of the set the checker settled to order them by. A case stands where that set declares it.
//!
//! The order a set's members and a map's keys stand in (`compare`), and the hash beside it
//! (`hash_of`): one value is one place, and the language says nothing more of where. Asked of the
//! values themselves — which type each is is the descriptor of the type it was made as, read off it
//! (`identity`) — because the descriptor a collection holds is the type it was written as where it
//! was made, which may be narrower than a value later put in it.
//!
//! The order a set is written in (`as_written`): ascending by what its members are written as, so
//! that one collection is one document however it was built. That order belongs to Souther and not
//! to this backend — it is `souther.runtime.Representations` — and it is asked of what was written,
//! read back, so it answers about the document and nothing else. Null first, then false, true,
//! numbers, strings, arrays and objects. Numbers by the amount and then by the way each is written;
//! strings by scalar value, which is the language's order on text and 199x-notation's; arrays
//! element by element with the shorter first; objects as their members read in key order.
//!
//! Three because each is asked of something different, and two of them answered by one function
//! is what put a set in an order the JVM does not write it in, and took a case met as one type for
//! another.

use crate::decimal;
use crate::descriptor::{
    self, Carried, KIND_BOOL, KIND_DATE, KIND_DATE_TIME, KIND_DECIMAL, KIND_ENUMERATION, KIND_INSTANT,
    KIND_INT, KIND_NEWTYPE,
    KIND_LIST, KIND_MAP, KIND_OPTION, KIND_PRODUCT, KIND_RATIONAL, KIND_SET, KIND_STRING, KIND_SUM,
    KIND_TIME, KIND_TUPLE, KIND_UNIT,
};
use crate::json;
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
        // By exact value, which is the order the language states for them; `compare` places them
        // by their parts instead, which is no order the language states.
        KIND_RATIONAL => descriptor::ordered_exactly(descriptor, left, right),
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
            let (at, length) = decimal::external(cell);
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
        // Which type it is and what it carries, asked of the value as `compare` asks it, so that
        // one value has one hash whatever type the place holding it was written as: a unit, a
        // shape, a set of units and a sum listing them hash a value alike, a set not listing it
        // still hashes it as itself, and a primitive among cases hashes as that primitive.
        KIND_UNIT | KIND_PRODUCT | KIND_ENUMERATION | KIND_SUM => {
            let case = identity(cell);
            let which = mixed(HASH_START, case);
            match descriptor::carried(case) {
                Carried::Nothing => which,
                Carried::Fields => {
                    let mut hash = which;
                    for i in 0..descriptor::arity(case) {
                        hash = mixed(hash, hash_of(value::__souther_record_get(cell, i), descriptor::member(case, i)));
                    }
                    hash
                }
                Carried::Itself => mixed(which, hash_of(cell, case)),
            }
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

/// Where a value stands relative to another among a set's members or a map's keys, and whether
/// they are one value.
///
/// An order the language leaves open, so it is this runtime's to keep: a value of a declared type
/// by what it was made as (`declared`), and the rest much as they are written, which was once what
/// this was for. It is not the order a set is written in, which `as_written` asks of the written
/// document.
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
                // By the parts each is, which are one value's own, so two are one exactly where
                // they are one value. Nothing writes one, so it has no place among written values,
                // and the order a set of them stands in is the language's to leave open — so it is
                // not the order of their values, which is exact arithmetic and is `ranked`'s to
                // answer. Asked of the cells alone, a rational met as one of a set's alternatives
                // is placed by this whatever descriptor reached it, and no set carries arithmetic.
                let (a, a_length) = rational::parts(left);
                let (b, b_length) = rational::parts(right);
                return bytewise(a, a_length, b, b_length);
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

/// Where one written value stands against another: two documents as `json` reads them, in the
/// order `souther.runtime.Representations.compareExternalForms` puts external representations in.
///
/// Null first, then false, true, numbers, strings, arrays and objects. Numbers by the amount and
/// then by the way each is written; strings by scalar value; arrays element by element with the
/// shorter first; objects as their members read in the order of their keys, key against key and
/// then value against value, with the one holding fewer first.
pub unsafe fn as_written(left: u32, right: u32) -> i32 {
    let (a, b) = (json::__souther_json_tag(left), json::__souther_json_tag(right));
    if a != b {
        return if a < b { -1 } else { 1 };
    }
    match a {
        json::TAG_NUMBER => {
            let (x, x_length) = (json::__souther_json_bytes(left), json::__souther_json_length(left));
            let (y, y_length) =
                (json::__souther_json_bytes(right), json::__souther_json_length(right));
            let by_amount = amounts(x, x_length, y, y_length);
            if by_amount != 0 {
                return by_amount;
            }
            bytewise(x, x_length, y, y_length)
        }
        json::TAG_STRING => compare_runs(
            json::__souther_json_bytes(left),
            json::__souther_json_length(left),
            json::__souther_json_bytes(right),
            json::__souther_json_length(right),
        ),
        json::TAG_ARRAY => {
            let (m, n) = (json::__souther_json_length(left), json::__souther_json_length(right));
            for i in 0..m.min(n) {
                let each = as_written(
                    json::__souther_json_element(left, i),
                    json::__souther_json_element(right, i),
                );
                if each != 0 {
                    return each;
                }
            }
            sign_of_difference(m, n)
        }
        // Read in the order `keyed` put the entries in, once, before any of this was asked.
        json::TAG_OBJECT => {
            let (m, n) = (json::__souther_json_length(left), json::__souther_json_length(right));
            for i in 0..m.min(n) {
                let (x, y) = (json::__souther_json_key(left, i), json::__souther_json_key(right, i));
                let (x, x_length, y, y_length) = (
                    json::__souther_json_bytes(x),
                    json::__souther_json_length(x),
                    json::__souther_json_bytes(y),
                    json::__souther_json_length(y),
                );
                // Two values of one shape have one key at each place, so the bytes nearly always
                // say they are one key without asking the order of text.
                let by_key = if bytewise(x, x_length, y, y_length) == 0 {
                    0
                } else {
                    compare_runs(x, x_length, y, y_length)
                };
                if by_key != 0 {
                    return by_key;
                }
                let by_value =
                    as_written(json::__souther_json_value(left, i), json::__souther_json_value(right, i));
                if by_value != 0 {
                    return by_value;
                }
            }
            sign_of_difference(m, n)
        }
        _ => 0,
    }
}

/// Puts every object in a document in the order of its keys, all the way down, so that
/// `as_written` reads each in that order as it stands: done once for a document, where asking it of
/// each comparison would sort the same keys again every time two values are compared.
pub unsafe fn keyed(node: u32) {
    match json::__souther_json_tag(node) {
        json::TAG_ARRAY => {
            for i in 0..json::__souther_json_length(node) {
                keyed(json::__souther_json_element(node, i));
            }
        }
        json::TAG_OBJECT => {
            let held = json::__souther_json_length(node);
            let order = core::slice::from_raw_parts_mut(
                crate::alloc(4 * held.max(1)) as usize as *mut u32,
                held as usize,
            );
            for (i, each) in order.iter_mut().enumerate() {
                *each = i as u32;
            }
            sort_places(order, &|a, b| {
                let (x, y) = (json::__souther_json_key(node, a), json::__souther_json_key(node, b));
                compare_runs(
                    json::__souther_json_bytes(x),
                    json::__souther_json_length(x),
                    json::__souther_json_bytes(y),
                    json::__souther_json_length(y),
                )
            });
            let entries = alloc_pairs(held);
            for (to, &from) in order.iter().enumerate() {
                let value = json::__souther_json_value(node, from);
                keyed(value);
                *entries.add(2 * to) = json::__souther_json_key(node, from);
                *entries.add(2 * to + 1) = value;
            }
            json::put_entries(node, entries, held);
        }
        _ => {}
    }
}

unsafe fn alloc_pairs(held: u32) -> *mut u32 {
    crate::alloc(8 * held.max(1)) as usize as *mut u32
}

/// Two numbers as written, by the amount each writes.
///
/// Read off the text and never made into a value: a number this runtime wrote is any number it
/// can hold, and making one again is a reader with a reader's bounds, which the writer's are not
/// — `1E+2147483648` is a `Decimal` held at the lowest scale, and a reader adding up its exponent
/// would refuse it. So the amount is its sign, how far from the point its first digit stands, and
/// its digits from there with no zero after the last, compared in that order.
unsafe fn amounts(x: u32, x_length: u32, y: u32, y_length: u32) -> i32 {
    let (a, b) = (Amount::of(x, x_length), Amount::of(y, y_length));
    let (a_sign, b_sign) = (a.sign(), b.sign());
    if a_sign != b_sign {
        return if a_sign < b_sign { -1 } else { 1 };
    }
    if a_sign == 0 {
        return 0;
    }
    let further = if a.point != b.point {
        if a.point < b.point { -1 } else { 1 }
    } else {
        let (m, n) = (a.last - a.first, b.last - b.first);
        let mut by_digits = 0;
        for k in 0..m.min(n) {
            let (p, q) = (a.digit(a.first + k), b.digit(b.first + k));
            if p != q {
                by_digits = if p < q { -1 } else { 1 };
                break;
            }
        }
        if by_digits == 0 { sign_of_difference(m, n) } else { by_digits }
    };
    if a_sign < 0 { -further } else { further }
}

/// A number as JSON writes one, read for its amount: `[-]digits[.digits][e[+|-]digits]`.
struct Amount {
    negative: bool,
    whole: *const u8,
    whole_length: u32,
    fraction: *const u8,
    /// The digits that are not zeros at either end, as places in the whole and the fraction read
    /// as one run.
    first: u32,
    last: u32,
    /// Where the point stands, counted from before the first digit that is not a zero.
    point: i64,
}

impl Amount {
    unsafe fn of(at: u32, length: u32) -> Amount {
        let bytes = core::slice::from_raw_parts(at as usize as *const u8, length as usize);
        let mut i = 0;
        let negative = bytes.first() == Some(&b'-');
        if negative {
            i += 1;
        }
        let whole_at = i;
        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
        }
        let whole_length = (i - whole_at) as u32;
        let mut fraction_at = i;
        if i < bytes.len() && bytes[i] == b'.' {
            i += 1;
            fraction_at = i;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
        }
        let fraction_length = (i - fraction_at) as u32;
        let mut exponent: i64 = 0;
        if i < bytes.len() && (bytes[i] | 0x20) == b'e' {
            i += 1;
            let down = bytes.get(i) == Some(&b'-');
            if matches!(bytes.get(i), Some(b'-' | b'+')) {
                i += 1;
            }
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                // Held short of overflowing: no exponent the writer writes is near this.
                exponent = (exponent * 10 + i64::from(bytes[i] - b'0')).min(1 << 50);
                i += 1;
            }
            if down {
                exponent = -exponent;
            }
        }
        let mut amount = Amount {
            negative,
            whole: bytes.as_ptr().add(whole_at),
            whole_length,
            fraction: bytes.as_ptr().add(fraction_at),
            first: 0,
            last: whole_length + fraction_length,
            point: 0,
        };
        while amount.first < amount.last && amount.digit(amount.first) == b'0' {
            amount.first += 1;
        }
        while amount.last > amount.first && amount.digit(amount.last - 1) == b'0' {
            amount.last -= 1;
        }
        amount.point = i64::from(whole_length) - i64::from(amount.first) + exponent;
        amount
    }

    unsafe fn digit(&self, at: u32) -> u8 {
        if at < self.whole_length {
            *self.whole.add(at as usize)
        } else {
            *self.fraction.add((at - self.whole_length) as usize)
        }
    }

    /// Below nought, nought, or above it.
    fn sign(&self) -> i32 {
        if self.first == self.last {
            0
        } else if self.negative {
            -1
        } else {
            1
        }
    }
}

/// Puts places in the order `against` says, merged in runs that double.
///
/// One function for every caller, handed the order as a value rather than compiled once per order:
/// the library's sort is compiled again for each, which every module that writes anything would
/// carry several times over.
pub unsafe fn sort_places(places: &mut [u32], against: &dyn Fn(u32, u32) -> i32) {
    let held = places.len();
    if held < 2 {
        return;
    }
    let other = core::slice::from_raw_parts_mut(crate::alloc(4 * held as u32) as usize as *mut u32, held);
    let mut width = 1;
    let (mut from, mut into) = (places.as_mut_ptr(), other.as_mut_ptr());
    while width < held {
        let mut at = 0;
        while at < held {
            let middle = (at + width).min(held);
            let end = (at + 2 * width).min(held);
            let (mut i, mut j, mut k) = (at, middle, at);
            while i < middle && j < end {
                // The earlier run's first where the two stand level, so the order is stable.
                if against(*from.add(j), *from.add(i)) < 0 {
                    *into.add(k) = *from.add(j);
                    j += 1;
                } else {
                    *into.add(k) = *from.add(i);
                    i += 1;
                }
                k += 1;
            }
            while i < middle {
                *into.add(k) = *from.add(i);
                i += 1;
                k += 1;
            }
            while j < end {
                *into.add(k) = *from.add(j);
                j += 1;
                k += 1;
            }
            at = end;
        }
        core::mem::swap(&mut from, &mut into);
        width *= 2;
    }
    if from != places.as_mut_ptr() {
        core::ptr::copy_nonoverlapping(from, places.as_mut_ptr(), held);
    }
}

fn sign_of_difference(m: u32, n: u32) -> i32 {
    if m < n {
        -1
    } else if m > n {
        1
    } else {
        0
    }
}

/// Two runs of bytes, byte by byte, the shorter first where one begins the other.
unsafe fn bytewise(at: u32, length: u32, other: u32, other_length: u32) -> i32 {
    let a = core::slice::from_raw_parts(at as usize as *const u8, length as usize);
    let b = core::slice::from_raw_parts(other as usize as *const u8, other_length as usize);
    match a.cmp(b) {
        core::cmp::Ordering::Less => -1,
        core::cmp::Ordering::Equal => 0,
        core::cmp::Ordering::Greater => 1,
    }
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
/// carries, as `descriptor::carried` says it carries it: nothing, its fields one by one, or itself,
/// read by its own descriptor — a newtype case by what it wraps.
unsafe fn declared(left: u32, right: u32) -> i32 {
    let by_case = cases(left, right);
    if by_case != 0 {
        return by_case;
    }
    let own = identity(left);
    match descriptor::carried(own) {
        Carried::Nothing => 0,
        Carried::Fields => {
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
        Carried::Itself => compare(left, right, own),
    }
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

/// What a value is, read off the value: the descriptor of the type it was made as.
///
/// Asked of the value and not of the set of alternatives it is met as, because that set is
/// whatever descriptor reached this — a collection's own, written where it was made, may be a
/// union narrower than the sum a value later put in it was made as, and does not list that value
/// at all. One type is one descriptor wherever it is listed, so this is the same answer for every
/// set that lists it.
///
/// Every value a set of alternatives can hold has one. A value of a declared type — a unit, a
/// shape, a newtype — holds it in its cell. A primitive holds none, and is the primitive its tag
/// says, which this runtime holds the descriptor of (`descriptor::primitive`). A list, a map, an
/// option or a tuple is never one of a set's alternatives, which are named types, so a cell of one
/// met here ends the call rather than standing for something it is not.
pub unsafe fn identity(cell: u32) -> u32 {
    let tag = core::ptr::read_unaligned(cell as usize as *const u32);
    descriptor::primitive(match tag {
        value::TAG_UNIT | value::TAG_RECORD => {
            return core::ptr::read_unaligned((cell as usize + 4) as *const u32);
        }
        value::TAG_INT => KIND_INT,
        value::TAG_BOOL => KIND_BOOL,
        value::TAG_STRING => KIND_STRING,
        value::TAG_DECIMAL => KIND_DECIMAL,
        value::TAG_RATIONAL => KIND_RATIONAL,
        value::TAG_DATE => KIND_DATE,
        value::TAG_TIME => KIND_TIME,
        value::TAG_DATE_TIME => KIND_DATE_TIME,
        value::TAG_INSTANT => KIND_INSTANT,
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, 0, other as u64, cell as u64),
    })
}

/// Two values a set of alternatives holds, by which type each is: by the names they are written
/// as, and two that share a name — one case from each of two sums — by which they are, so that
/// only one type is one. Neither is asked of a set of alternatives, so a value one does not list
/// stands in its place too.
unsafe fn cases(left: u32, right: u32) -> i32 {
    let (a, b) = (identity(left), identity(right));
    if a == b {
        return 0;
    }
    let (first, first_length) = descriptor::called(a);
    let (second, second_length) = descriptor::called(b);
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
    match value::member_of(cell, descriptor) {
        Some(i) => i,
        None => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, cell as u64, 0),
    }
}
