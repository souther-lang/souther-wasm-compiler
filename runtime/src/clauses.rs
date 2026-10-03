//! What a value read at the boundary that breaks a clause of its type is reported as.
//!
//! Which issue a broken clause is, is the language's to say (spec §decoder-error), and the
//! compiler wrote down what it says for each clause in a table the type's descriptor points at.
//! A newtype's clause that is a standard constraint is reported as that constraint — its code, its
//! message key and its metadata, as Raoh's own constraint reports it — and any other clause as an
//! invariant violation naming the module, the type, and the clause where it has a name.
//!
//! ```text
//! +0  u32 where the module's name is, u32 how long
//! +8  u32 how many clauses
//! +12 per clause: u32 where its name is, u32 how long, u32 where its constraints are,
//!                 u32 how many, u32 whether they are the whole clause
//!
//! a constraint: u32 its rule, then four u32 its rule reads as it says
//! ```
//!
//! What is decided here is only which of a clause's constraints the value breaks, which is asked
//! of the value; what the clause is as constraints was decided by the checker, and the runtime
//! renders it.

use crate::descriptor;
use crate::issues;
use crate::issues::meta;
use crate::value;

/// `String.length(value) >= n`: `n`.
pub const RULE_MIN_LENGTH: u32 = 1;
/// `String.length(value) <= n`: `n`.
pub const RULE_MAX_LENGTH: u32 = 2;
/// `String.length(value) == n`: `n`.
pub const RULE_FIXED_LENGTH: u32 = 3;
/// `String.matches(p, value)`: where `p` is written and how long, and where its machine's image is.
pub const RULE_PATTERN: u32 = 4;
/// `value >= n` of an `Int`: `n`'s low word and its high one.
pub const RULE_MIN: u32 = 5;
/// `value <= n` of an `Int`: `n`'s low word and its high one.
pub const RULE_MAX: u32 = 6;
/// `value > 0` of an `Int`.
pub const RULE_POSITIVE: u32 = 7;
/// `value >= 0` of an `Int`.
pub const RULE_NON_NEGATIVE: u32 = 8;
/// `value >= n` of a `Decimal`: where `n` is written and how long.
pub const RULE_DECIMAL_MIN: u32 = 9;
/// `value <= n` of a `Decimal`: where `n` is written and how long.
pub const RULE_DECIMAL_MAX: u32 = 10;
/// `value > 0` of a `Decimal`.
pub const RULE_DECIMAL_POSITIVE: u32 = 11;
/// `value >= 0` of a `Decimal`.
pub const RULE_DECIMAL_NON_NEGATIVE: u32 = 12;
/// `List.length(value) >= 1`.
pub const RULE_NON_EMPTY: u32 = 13;
/// `List.length(value) >= n`: `n`.
pub const RULE_MIN_SIZE: u32 = 14;
/// `List.length(value) <= n`: `n`.
pub const RULE_MAX_SIZE: u32 = 15;
/// `List.length(value) == n`: `n`.
pub const RULE_FIXED_SIZE: u32 = 16;
/// `List.allDistinctBy(x -> x, value)`.
pub const RULE_UNIQUE: u32 = 17;
/// `Map.size(value) >= 1`.
pub const RULE_MAP_NON_EMPTY: u32 = 18;
/// `Map.size(value) >= n`: `n`.
pub const RULE_MAP_MIN_SIZE: u32 = 19;
/// `Map.size(value) <= n`: `n`.
pub const RULE_MAP_MAX_SIZE: u32 = 20;

const CLAUSE: u32 = 20;
const CONSTRAINT: u32 = 20;

/// Reports the clause `clause` of `descriptor`'s type, which `cell` breaks, at `path`.
pub unsafe fn broken(cell: u32, descriptor: u32, clause: u32, path: u32, path_length: u32) {
    let table = descriptor::clauses(descriptor);
    let entry = table + 12 + CLAUSE * clause;
    let constraints = read(entry + 8);
    let count = read(entry + 12);
    if count > 0 {
        // A newtype holds one value, and a constraint is about it.
        let held = value::__souther_record_get(cell, 0);
        let of = descriptor::member(descriptor, 0);
        for k in 0..count {
            if constrained(held, of, constraints + CONSTRAINT * k, path, path_length) {
                return;
            }
        }
    }
    // A clause that is no constraint, or one whose constraints the value meets though it breaks the
    // clause: the rule it is, named.
    meta::begin();
    meta::text(b"module", read(table), read(table + 4));
    let (named, named_length) = descriptor::own_name(descriptor);
    meta::text(b"type", named, named_length);
    if read(entry + 4) > 0 {
        meta::text(b"clause", read(entry), read(entry + 4));
    }
    issues::issue(issues::CODE_INVARIANT_VIOLATION, path, path_length, meta::end());
}

/// Whether `held` breaks the constraint at `at`, reporting it where it does.
unsafe fn constrained(held: u32, of: u32, at: u32, path: u32, path_length: u32) -> bool {
    let (a, b, c) = (read(at + 4), read(at + 8), read(at + 12));
    match read(at) {
        RULE_MIN_LENGTH => sized(code_points(held), a as i64, None, b"too_short", b"too_short",
            b"min", path, path_length),
        RULE_MAX_LENGTH => sized(code_points(held), i64::MIN, Some(a as i64),
            b"too_long", b"too_long", b"max", path, path_length),
        RULE_FIXED_LENGTH => exactly(code_points(held), a as i64, b"invalid_length", path,
            path_length),
        RULE_PATTERN => {
            if value::__souther_bool_value(crate::kernel::__souther_string_matches(held, c)) != 0 {
                return false;
            }
            meta::begin();
            meta::text(b"pattern", a, b);
            issues::issue(b"invalid_format", path, path_length, meta::end());
            true
        }
        RULE_MIN => bounded(value::__souther_int_value(held), wide(a, b), b"out_of_range.minimum",
            b"min", true, path, path_length),
        RULE_MAX => bounded(value::__souther_int_value(held), wide(a, b), b"out_of_range.maximum",
            b"max", false, path, path_length),
        RULE_POSITIVE => bounded(value::__souther_int_value(held), 1, b"out_of_range.positive",
            b"min", true, path, path_length),
        RULE_NON_NEGATIVE => bounded(value::__souther_int_value(held), 0,
            b"out_of_range.non_negative", b"min", true, path, path_length),
        RULE_DECIMAL_MIN => amount(held, crate::decimal::parse(a, b), b"out_of_range.minimum",
            b"min", true, path, path_length),
        RULE_DECIMAL_MAX => amount(held, crate::decimal::parse(a, b), b"out_of_range.maximum",
            b"max", false, path, path_length),
        RULE_DECIMAL_POSITIVE => {
            let zero = crate::decimal::parse(b"0".as_ptr() as u32, 1);
            if crate::decimal::compare(held, zero) > 0 {
                return false;
            }
            out_of_range_amount(held, zero, b"out_of_range.positive", b"min", path, path_length);
            true
        }
        RULE_DECIMAL_NON_NEGATIVE => amount(held, crate::decimal::parse(b"0".as_ptr() as u32, 1),
            b"out_of_range.non_negative", b"min", true, path, path_length),
        RULE_NON_EMPTY => {
            let n = value::__souther_list_length(held) as i64;
            sized(n, 1, None, b"too_small", b"too_small.nonempty", b"min", path, path_length)
        }
        RULE_MIN_SIZE => sized(value::__souther_list_length(held) as i64, a as i64, None,
            b"too_small", b"too_small", b"min", path, path_length),
        RULE_MAX_SIZE => sized(value::__souther_list_length(held) as i64, i64::MIN,
            Some(a as i64), b"too_big", b"too_big", b"max", path, path_length),
        RULE_FIXED_SIZE => exactly(value::__souther_list_length(held) as i64, a as i64,
            b"invalid_size", path, path_length),
        RULE_UNIQUE => duplicated(held, descriptor::member(of, 0), path, path_length),
        RULE_MAP_NON_EMPTY => sized(value::__souther_map_length(held) as i64, 1, None,
            b"too_small", b"too_small.nonempty", b"min", path, path_length),
        RULE_MAP_MIN_SIZE => sized(value::__souther_map_length(held) as i64, a as i64, None,
            b"too_small", b"too_small", b"min", path, path_length),
        RULE_MAP_MAX_SIZE => sized(value::__souther_map_length(held) as i64, i64::MIN,
            Some(a as i64), b"too_big", b"too_big", b"max", path, path_length),
        // Nothing a caller wrote reaches this: the table is the compiler's.
        other => crate::abort(crate::REASON_BACKEND_INVARIANT_BROKEN, 0, other as u64, 0),
    }
}

/// A count held to at least `min`, or to at most `max` where there is one.
#[allow(clippy::too_many_arguments)]
unsafe fn sized(
    actual: i64,
    min: i64,
    max: Option<i64>,
    code: &'static [u8],
    key: &'static [u8],
    bound: &[u8],
    path: u32,
    path_length: u32,
) -> bool {
    let limit = match max {
        Some(max) if actual > max => max,
        None if actual < min => min,
        _ => return false,
    };
    meta::begin();
    meta::integer(b"actual", actual);
    meta::integer(bound, limit);
    issues::keyed(code, key, path, path_length, meta::end());
    true
}

/// A count held to exactly `expected`.
unsafe fn exactly(actual: i64, expected: i64, code: &'static [u8], path: u32, path_length: u32) -> bool {
    if actual == expected {
        return false;
    }
    meta::begin();
    meta::integer(b"actual", actual);
    meta::integer(b"expected", expected);
    issues::issue(code, path, path_length, meta::end());
    true
}

/// A whole number held to at least, or at most, `limit`.
unsafe fn bounded(
    actual: i64,
    limit: i64,
    key: &'static [u8],
    bound: &[u8],
    lower: bool,
    path: u32,
    path_length: u32,
) -> bool {
    if (lower && actual >= limit) || (!lower && actual <= limit) {
        return false;
    }
    meta::begin();
    meta::integer(b"actual", actual);
    meta::integer(bound, limit);
    issues::keyed(b"out_of_range", key, path, path_length, meta::end());
    true
}

/// An amount held to at least, or at most, `limit`.
unsafe fn amount(
    held: u32,
    limit: u32,
    key: &'static [u8],
    bound: &[u8],
    lower: bool,
    path: u32,
    path_length: u32,
) -> bool {
    let order = crate::decimal::compare(held, limit);
    if (lower && order >= 0) || (!lower && order <= 0) {
        return false;
    }
    out_of_range_amount(held, limit, key, bound, path, path_length);
    true
}

unsafe fn out_of_range_amount(
    held: u32,
    limit: u32,
    key: &'static [u8],
    bound: &[u8],
    path: u32,
    path_length: u32,
) {
    meta::begin();
    let (at, length) = crate::decimal::written(held);
    meta::raw(b"actual", at, length);
    let (at, length) = crate::decimal::written(limit);
    meta::raw(bound, at, length);
    issues::keyed(b"out_of_range", key, path, path_length, meta::end());
}

/// A list holding some value more than once, reported with each such value once, in the order
/// its second writing was met.
unsafe fn duplicated(held: u32, element: u32, path: u32, path_length: u32) -> bool {
    let n = value::__souther_list_length(held);
    // The values found twice, kept in the arena as the call's other workings are.
    let duplicates = crate::alloc(4 * n.max(1));
    let mut found = 0u32;
    for i in 0..n {
        let each = value::__souther_list_get(held, i);
        let seen_before = (0..i).any(|j| {
            crate::order::compare(value::__souther_list_get(held, j), each, element) == 0
        });
        let already = (0..found).any(|k| crate::order::compare(read(duplicates + 4 * k), each,
            element) == 0);
        if seen_before && !already {
            core::ptr::write_unaligned((duplicates + 4 * found) as *mut u32, each);
            found += 1;
        }
    }
    if found == 0 {
        return false;
    }
    meta::begin();
    crate::text::begin();
    crate::text::put(b"[");
    for k in 0..found {
        if k > 0 {
            crate::text::put(b",");
        }
        value::written(read(duplicates + 4 * k), element);
    }
    crate::text::put(b"]");
    let (at, length) = crate::text::ended();
    meta::raw(b"duplicates", at, length);
    issues::issue(b"duplicate_element", path, path_length, meta::end());
    true
}

/// How many code points a `String` holds, which is what `String.length` counts.
unsafe fn code_points(text: u32) -> i64 {
    let at = value::__souther_string_bytes(text);
    let length = value::__souther_string_length(text);
    let mut count = 0;
    for i in 0..length {
        if core::ptr::read((at + i) as *const u8) & 0xC0 != 0x80 {
            count += 1;
        }
    }
    count
}

fn wide(low: u32, high: u32) -> i64 {
    ((high as u64) << 32 | low as u64) as i64
}

unsafe fn read(at: u32) -> u32 {
    core::ptr::read_unaligned(at as *const u32)
}
