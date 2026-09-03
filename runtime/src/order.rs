//! Where one value is written relative to another.
//!
//! A `Set` and a `Map` are written in ascending order of what their members are written as, so that
//! one collection is one document however it was built. What that order is belongs to Souther and
//! not to this backend: it is `souther.runtime.Representations`, and this is that order over the
//! values this runtime holds rather than over the forms a JVM encoder makes of them.
//!
//! Null first, then false, true, numbers, strings, arrays and objects. Numbers by the amount and
//! then by the way it is written; strings by UTF-16 code unit; arrays element by element with the
//! shorter first; objects as their members read in key order.
//!
//! # Why code units
//!
//! A JVM string compares by `char`, which is a UTF-16 code unit, and what is held here is UTF-8.
//! The two disagree: a character past the basic plane is one code point above every code unit and
//! two surrogates below `U+E000`, so `"\u{10000}"` sorts after `"\u{FFFF}"` by code point and
//! before it by code unit. Comparing the bytes would put a set in an order the JVM backend does
//! not write, which is the whole thing this order exists to stop.

use crate::descriptor::{
    self, KIND_BOOL, KIND_INT, KIND_LIST, KIND_MAP, KIND_OPTION, KIND_PRODUCT, KIND_SET,
    KIND_STRING, KIND_SUM, KIND_UNIT,
};
use crate::value;

const RANK_NULL: i32 = 0;
const RANK_FALSE: i32 = 1;
const RANK_TRUE: i32 = 2;
const RANK_NUMBER: i32 = 3;
const RANK_STRING: i32 = 4;
const RANK_ARRAY: i32 = 5;
const RANK_OBJECT: i32 = 6;

/// Where a value is written relative to another of the same type.
pub unsafe fn compare(left: u32, right: u32, descriptor: u32) -> i32 {
    let a = rank(left, descriptor);
    let b = rank(right, descriptor);
    if a != b {
        return if a < b { -1 } else { 1 };
    }
    match a {
        RANK_NULL | RANK_FALSE | RANK_TRUE => 0,
        RANK_NUMBER => {
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
        RANK_STRING => text(left, right),
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
        KIND_INT => RANK_NUMBER,
        KIND_STRING => RANK_STRING,
        KIND_LIST | KIND_SET => RANK_ARRAY,
        KIND_OPTION => {
            if held(cell) == 0 {
                RANK_NULL
            } else {
                rank(held(cell), descriptor::member(descriptor, 0))
            }
        }
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

/// Two strings by UTF-16 code unit, which is what a JVM string compares by.
pub unsafe fn compare_text(left: u32, right: u32) -> i32 {
    text(left, right)
}

unsafe fn text(left: u32, right: u32) -> i32 {
    let mut a = Units::over(
        value::__souther_string_bytes(left),
        value::__souther_string_length(left),
    );
    let mut b = Units::over(
        value::__souther_string_bytes(right),
        value::__souther_string_length(right),
    );
    loop {
        match (a.next(), b.next()) {
            (None, None) => return 0,
            (None, Some(_)) => return -1,
            (Some(_), None) => return 1,
            (Some(x), Some(y)) if x != y => return if x < y { -1 } else { 1 },
            _ => {}
        }
    }
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
    if descriptor::kind(descriptor) == KIND_UNIT {
        return 0;
    }
    if descriptor::kind(descriptor) == KIND_MAP {
        return entries(left, right, descriptor);
    }
    if descriptor::kind(descriptor) == KIND_SUM {
        // A sum's members are the tag and then the case's own, so which case each is decides
        // first — by the tag, which is a string like any other.
        let (a, b) = (case_of(left, descriptor), case_of(right, descriptor));
        if a != b {
            let (first, first_length) = descriptor::name(descriptor, a);
            let (second, second_length) = descriptor::name(descriptor, b);
            return bytes_as_units(first, first_length, second, second_length);
        }
        return members(left, right, descriptor::member(descriptor, a));
    }
    for i in 0..descriptor::arity(descriptor) {
        let member = descriptor::member(descriptor, i);
        let each = compare(
            value::__souther_record_get(left, i),
            value::__souther_record_get(right, i),
            member,
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
    let values = descriptor::member(descriptor, 1);
    let a = value::__souther_map_length(left);
    let b = value::__souther_map_length(right);
    let shorter = if a < b { a } else { b };
    for i in 0..shorter {
        let by_key = text(
            value::__souther_map_key(left, i),
            value::__souther_map_key(right, i),
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

unsafe fn case_of(cell: u32, descriptor: u32) -> u32 {
    let own = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
    for i in 0..descriptor::arity(descriptor) {
        if descriptor::member(descriptor, i) == own {
            return i;
        }
    }
    0
}

unsafe fn bytes_as_units(left: u32, left_length: u32, right: u32, right_length: u32) -> i32 {
    let mut a = Units::over(left, left_length);
    let mut b = Units::over(right, right_length);
    loop {
        match (a.next(), b.next()) {
            (None, None) => return 0,
            (None, Some(_)) => return -1,
            (Some(_), None) => return 1,
            (Some(x), Some(y)) if x != y => return if x < y { -1 } else { 1 },
            _ => {}
        }
    }
}

/// UTF-8 bytes, read out as the UTF-16 code units they stand for.
struct Units {
    at: usize,
    end: usize,
    pending: u32,
}

impl Units {
    fn over(pointer: u32, length: u32) -> Units {
        Units { at: pointer as usize, end: (pointer + length) as usize, pending: 0 }
    }

    unsafe fn next(&mut self) -> Option<u32> {
        if self.pending != 0 {
            let low = self.pending;
            self.pending = 0;
            return Some(low);
        }
        if self.at >= self.end {
            return None;
        }
        let first = core::ptr::read(self.at as *const u8) as u32;
        let (point, width) = if first < 0x80 {
            (first, 1)
        } else if first < 0xe0 {
            (((first & 0x1f) << 6) | self.trailing(1), 2)
        } else if first < 0xf0 {
            (((first & 0x0f) << 12) | (self.trailing(1) << 6) | self.trailing(2), 3)
        } else {
            (
                ((first & 0x07) << 18)
                    | (self.trailing(1) << 12)
                    | (self.trailing(2) << 6)
                    | self.trailing(3),
                4,
            )
        };
        self.at += width;
        if point > 0xffff {
            let rest = point - 0x10000;
            self.pending = 0xdc00 + (rest & 0x3ff);
            Some(0xd800 + (rest >> 10))
        } else {
            Some(point)
        }
    }

    unsafe fn trailing(&self, offset: usize) -> u32 {
        (core::ptr::read((self.at + offset) as *const u8) as u32) & 0x3f
    }
}
