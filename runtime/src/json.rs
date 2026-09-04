//! JSON as the neutral source a decoder reads.
//!
//! Parsing and deciding what a value is are two questions, and this file answers only the first.
//! What comes out is the document as it was written — an array is an array, a number is the digits
//! that were there — and which Souther type a given place holds is settled afterwards, by the
//! generated decoder that knows what was declared. That split is the one the JVM backend already
//! makes: a shape is read out of a neutral source, and the source does not know the shape.
//!
//! A number keeps its text. `1` and `1.0` are one amount and two ways of writing it, and which was
//! written decides the scale a `Decimal` reads back with, so the digits are carried rather than a
//! number this file chose to parse them into.
//!
//! # The cell
//!
//! Every parsed value is a cell in the arena:
//!
//! ```text
//! +0  u32 tag
//! +4  u32 length    bytes for a string or a number, entries for an array or an object
//! +8  payload       the bytes, or the entry pointers
//! ```
//!
//! An object's entries are pairs: a key cell and a value cell, in the order the document wrote
//! them. Nothing here sorts or deduplicates them — what a document said is what a decoder is
//! handed.

use crate::{abort, alloc, REASON_MALFORMED_JSON};

/// `null`.
pub const TAG_NULL: u32 = 0;
/// `false`.
pub const TAG_FALSE: u32 = 1;
/// `true`.
pub const TAG_TRUE: u32 = 2;
/// A number, whose payload is the digits as they were written.
pub const TAG_NUMBER: u32 = 3;
/// A string, whose payload is its unescaped UTF-8 bytes.
pub const TAG_STRING: u32 = 4;
/// An array, whose payload is one cell pointer per element.
pub const TAG_ARRAY: u32 = 5;
/// An object, whose payload is a key pointer and a value pointer per entry.
pub const TAG_OBJECT: u32 = 6;

const HEADER: usize = 8;

/// Parses a document and answers the cell it is.
///
/// Anything that is not one JSON document ends the call: a decoder reading a half-parsed value
/// would answer about bytes nobody wrote.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_parse(pointer: u32, length: u32) -> u32 {
    let mut reader =
        Reader { at: pointer as usize, end: (pointer + length) as usize, deep: 0 };
    let value = reader.value();
    reader.spaces();
    if reader.at != reader.end {
        abort(REASON_MALFORMED_JSON, 0, reader.at as u64, reader.end as u64);
    }
    value
}

/// What kind of value a cell is.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_tag(cell: u32) -> u32 {
    read_u32(cell as usize)
}

/// A cell's length: bytes for a string or a number, entries for an array or an object.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_length(cell: u32) -> u32 {
    read_u32(cell as usize + 4)
}

/// Where a string's or a number's bytes start.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_bytes(cell: u32) -> u32 {
    cell + HEADER as u32
}

/// The element at an index of an array.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_element(cell: u32, index: u32) -> u32 {
    read_u32(cell as usize + HEADER + 4 * index as usize)
}

/// The key of an object's entry, which is a string cell.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_key(cell: u32, index: u32) -> u32 {
    read_u32(cell as usize + HEADER + 8 * index as usize)
}

/// The value of an object's entry.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_value(cell: u32, index: u32) -> u32 {
    read_u32(cell as usize + HEADER + 8 * index as usize + 4)
}

/// A whole number, written as JSON writes one.
///
/// Answers the arena pointer and the length packed low and high, which is how everything that
/// hands text back to a caller answers.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_write_int(value: i64) -> u64 {
    let mut digits = [0u8; 20];
    let mut written = 0;
    let negative = value < 0;
    // Negated as an i128 so that the most negative i64 has somewhere to go.
    let mut rest = if negative { -(value as i128) } else { value as i128 };
    if rest == 0 {
        digits[written] = b'0';
        written += 1;
    }
    while rest > 0 {
        digits[written] = b'0' + (rest % 10) as u8;
        written += 1;
        rest /= 10;
    }
    let total = written + usize::from(negative);
    let out = alloc(total as u32) as usize;
    let mut at = out;
    if negative {
        core::ptr::write(at as *mut u8, b'-');
        at += 1;
    }
    for i in (0..written).rev() {
        core::ptr::write(at as *mut u8, digits[i]);
        at += 1;
    }
    packed(out as u32, total as u32)
}

/// `true` or `false`.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_write_bool(value: u32) -> u64 {
    let text: &[u8] = if value != 0 { b"true" } else { b"false" };
    let out = alloc(text.len() as u32) as usize;
    core::ptr::copy_nonoverlapping(text.as_ptr(), out as *mut u8, text.len());
    packed(out as u32, text.len() as u32)
}

/// A string, quoted and escaped.
///
/// What JSON cannot carry as it stands is escaped, and nothing else is: a document that reads back
/// as different text from what went in would make the boundary a place where values change.
#[no_mangle]
pub unsafe extern "C" fn __souther_json_write_string(pointer: u32, length: u32) -> u64 {
    let mut needed = 2;
    for i in 0..length as usize {
        needed += escaped_width(read_u8(pointer as usize + i));
    }
    let out = alloc(needed as u32) as usize;
    let mut at = out;
    write_u8(&mut at, b'"');
    for i in 0..length as usize {
        escape(read_u8(pointer as usize + i), &mut at);
    }
    write_u8(&mut at, b'"');
    packed(out as u32, needed as u32)
}

fn escaped_width(byte: u8) -> usize {
    match byte {
        b'"' | b'\\' | 0x08 | 0x0c | b'\n' | b'\r' | b'\t' => 2,
        0x00..=0x1f => 6,
        _ => 1,
    }
}

unsafe fn escape(byte: u8, at: &mut usize) {
    match byte {
        b'"' => {
            write_u8(at, b'\\');
            write_u8(at, b'"');
        }
        b'\\' => {
            write_u8(at, b'\\');
            write_u8(at, b'\\');
        }
        0x08 => {
            write_u8(at, b'\\');
            write_u8(at, b'b');
        }
        0x0c => {
            write_u8(at, b'\\');
            write_u8(at, b'f');
        }
        b'\n' => {
            write_u8(at, b'\\');
            write_u8(at, b'n');
        }
        b'\r' => {
            write_u8(at, b'\\');
            write_u8(at, b'r');
        }
        b'\t' => {
            write_u8(at, b'\\');
            write_u8(at, b't');
        }
        0x00..=0x1f => {
            write_u8(at, b'\\');
            write_u8(at, b'u');
            write_u8(at, b'0');
            write_u8(at, b'0');
            write_u8(at, hex(byte >> 4));
            write_u8(at, hex(byte & 0x0f));
        }
        other => write_u8(at, other),
    }
}

fn hex(nibble: u8) -> u8 {
    if nibble < 10 {
        b'0' + nibble
    } else {
        b'a' + nibble - 10
    }
}

/// A pointer and a length in one result, low half and high.
pub fn packed(pointer: u32, length: u32) -> u64 {
    (pointer as u64) | ((length as u64) << 32)
}

unsafe fn read_u8(at: usize) -> u8 {
    core::ptr::read(at as *const u8)
}

unsafe fn write_u8(at: &mut usize, byte: u8) {
    core::ptr::write(*at as *mut u8, byte);
    *at += 1;
}

unsafe fn read_u32(at: usize) -> u32 {
    core::ptr::read_unaligned(at as *const u32)
}

unsafe fn write_u32(at: usize, value: u32) {
    core::ptr::write_unaligned(at as *mut u32, value);
}

/// A position in the document, and the readings that move it.
struct Reader {
    at: usize,
    end: usize,
    /// How far in the walk has descended, against how far it may.
    deep: u32,
}

/// How far one document may be nested.
///
/// A walk into an array or an object is a call, and a machine's stack is not something a caller
/// may write down how much of. Past this the document is refused the way anything that is not one
/// document is refused — with what a caller can read — rather than by the stack running out, which
/// is a fault of this module and reads as one.
const AS_DEEP_AS: u32 = 200;

impl Reader {
    /// Goes one deeper, or says the document is nested past what one may be.
    fn descended(&mut self) -> bool {
        self.deep += 1;
        self.deep <= AS_DEEP_AS
    }

    unsafe fn value(&mut self) -> u32 {
        self.spaces();
        match self.peek() {
            b'n' => self.keyword(b"null", TAG_NULL),
            b't' => self.keyword(b"true", TAG_TRUE),
            b'f' => self.keyword(b"false", TAG_FALSE),
            b'"' => self.string(),
            b'[' => self.array(),
            b'{' => self.object(),
            b'-' | b'0'..=b'9' => self.number(),
            _ => self.malformed(),
        }
    }

    unsafe fn keyword(&mut self, word: &[u8], tag: u32) -> u32 {
        if self.end - self.at < word.len() {
            self.malformed();
        }
        for (i, expected) in word.iter().enumerate() {
            if read_u8(self.at + i) != *expected {
                self.malformed();
            }
        }
        self.at += word.len();
        cell(tag, 0)
    }

    unsafe fn number(&mut self) -> u32 {
        let start = self.at;
        if self.peek() == b'-' {
            self.at += 1;
        }
        // A leading zero is not a way of writing a longer number, so `01` is two tokens rather
        // than one and the document is not one value.
        if self.peek() == b'0' {
            self.at += 1;
        } else {
            self.digits();
        }
        if self.at < self.end && read_u8(self.at) == b'.' {
            self.at += 1;
            self.digits();
        }
        if self.at < self.end && (read_u8(self.at) | 0x20) == b'e' {
            self.at += 1;
            if self.at < self.end && (read_u8(self.at) == b'+' || read_u8(self.at) == b'-') {
                self.at += 1;
            }
            self.digits();
        }
        let length = self.at - start;
        let out = cell(TAG_NUMBER, length as u32);
        let _ = alloc(length as u32);
        core::ptr::copy_nonoverlapping(start as *const u8, (out as usize + HEADER) as *mut u8, length);
        out
    }

    unsafe fn digits(&mut self) {
        let start = self.at;
        while self.at < self.end && read_u8(self.at).is_ascii_digit() {
            self.at += 1;
        }
        if self.at == start {
            self.malformed();
        }
    }

    unsafe fn string(&mut self) -> u32 {
        self.expect(b'"');
        // Unescaping only shortens, so the escaped span bounds what the unescaped bytes need.
        let room = self.end - self.at;
        let out = cell(TAG_STRING, 0);
        let _ = alloc(room as u32);
        let mut written = 0usize;
        let bytes = out as usize + HEADER;
        loop {
            if self.at >= self.end {
                self.malformed();
            }
            let byte = read_u8(self.at);
            self.at += 1;
            match byte {
                b'"' => break,
                b'\\' => written += self.escapee(bytes + written),
                other => {
                    core::ptr::write((bytes + written) as *mut u8, other);
                    written += 1;
                }
            }
        }
        write_u32(out as usize + 4, written as u32);
        out
    }

    /// Writes what one backslash escape stands for, and answers how many bytes that took.
    unsafe fn escapee(&mut self, out: usize) -> usize {
        if self.at >= self.end {
            self.malformed();
        }
        let byte = read_u8(self.at);
        self.at += 1;
        let plain = match byte {
            b'"' => b'"',
            b'\\' => b'\\',
            b'/' => b'/',
            b'b' => 0x08,
            b'f' => 0x0c,
            b'n' => b'\n',
            b'r' => b'\r',
            b't' => b'\t',
            b'u' => return self.codepoint(out),
            _ => self.malformed(),
        };
        core::ptr::write(out as *mut u8, plain);
        1
    }

    /// Writes what a `\u` escape stands for as UTF-8, joining a surrogate pair into the one
    /// character it is written as two halves of.
    unsafe fn codepoint(&mut self, out: usize) -> usize {
        let mut point = self.four_hex() as u32;
        if (0xd800..0xdc00).contains(&point) {
            if self.end - self.at < 2 || read_u8(self.at) != b'\\' || read_u8(self.at + 1) != b'u' {
                self.malformed();
            }
            self.at += 2;
            let low = self.four_hex() as u32;
            if !(0xdc00..0xe000).contains(&low) {
                self.malformed();
            }
            point = 0x10000 + ((point - 0xd800) << 10) + (low - 0xdc00);
        } else if (0xdc00..0xe000).contains(&point) {
            self.malformed();
        }
        let mut at = out;
        if point < 0x80 {
            write_u8(&mut at, point as u8);
        } else if point < 0x800 {
            write_u8(&mut at, 0xc0 | (point >> 6) as u8);
            write_u8(&mut at, 0x80 | (point & 0x3f) as u8);
        } else if point < 0x10000 {
            write_u8(&mut at, 0xe0 | (point >> 12) as u8);
            write_u8(&mut at, 0x80 | ((point >> 6) & 0x3f) as u8);
            write_u8(&mut at, 0x80 | (point & 0x3f) as u8);
        } else {
            write_u8(&mut at, 0xf0 | (point >> 18) as u8);
            write_u8(&mut at, 0x80 | ((point >> 12) & 0x3f) as u8);
            write_u8(&mut at, 0x80 | ((point >> 6) & 0x3f) as u8);
            write_u8(&mut at, 0x80 | (point & 0x3f) as u8);
        }
        at - out
    }

    unsafe fn four_hex(&mut self) -> u16 {
        if self.end - self.at < 4 {
            self.malformed();
        }
        let mut value = 0u16;
        for _ in 0..4 {
            let digit = match read_u8(self.at) {
                d @ b'0'..=b'9' => d - b'0',
                d @ b'a'..=b'f' => d - b'a' + 10,
                d @ b'A'..=b'F' => d - b'A' + 10,
                _ => self.malformed(),
            };
            value = (value << 4) | digit as u16;
            self.at += 1;
        }
        value
    }

    unsafe fn array(&mut self) -> u32 {
        if !self.descended() {
            self.malformed();
        }
        self.expect(b'[');
        self.spaces();
        let mut first = 0u32;
        let mut last = 0u32;
        let mut count = 0u32;
        if self.peek() != b']' {
            loop {
                let value = self.value();
                push(&mut first, &mut last, value);
                count += 1;
                self.spaces();
                match self.peek() {
                    b',' => self.at += 1,
                    b']' => break,
                    _ => self.malformed(),
                }
            }
        }
        self.expect(b']');
        let out = cell(TAG_ARRAY, count);
        let _ = alloc(4 * count);
        let mut node = first;
        for i in 0..count as usize {
            write_u32(out as usize + HEADER + 4 * i, read_u32(node as usize));
            node = read_u32(node as usize + 4);
        }
        out
    }

    unsafe fn object(&mut self) -> u32 {
        if !self.descended() {
            self.malformed();
        }
        self.expect(b'{');
        self.spaces();
        let mut first = 0u32;
        let mut last = 0u32;
        let mut count = 0u32;
        if self.peek() != b'}' {
            loop {
                self.spaces();
                if self.peek() != b'"' {
                    self.malformed();
                }
                let key = self.string();
                self.spaces();
                self.expect(b':');
                let value = self.value();
                push(&mut first, &mut last, key);
                push(&mut first, &mut last, value);
                count += 1;
                self.spaces();
                match self.peek() {
                    b',' => self.at += 1,
                    b'}' => break,
                    _ => self.malformed(),
                }
            }
        }
        self.expect(b'}');
        let out = cell(TAG_OBJECT, count);
        let _ = alloc(8 * count);
        let mut node = first;
        for i in 0..2 * count as usize {
            write_u32(out as usize + HEADER + 4 * i, read_u32(node as usize));
            node = read_u32(node as usize + 4);
        }
        out
    }

    unsafe fn spaces(&mut self) {
        while self.at < self.end {
            match read_u8(self.at) {
                b' ' | b'\t' | b'\n' | b'\r' => self.at += 1,
                _ => return,
            }
        }
    }

    unsafe fn peek(&mut self) -> u8 {
        if self.at >= self.end {
            self.malformed();
        }
        read_u8(self.at)
    }

    unsafe fn expect(&mut self, byte: u8) {
        if self.peek() != byte {
            self.malformed();
        }
        self.at += 1;
    }

    unsafe fn malformed(&self) -> ! {
        abort(REASON_MALFORMED_JSON, 0, self.at as u64, self.end as u64)
    }
}

/// Adds a cell to the end of a list being built in the arena.
///
/// A container's elements are held this way while they are read: how many there are is not known
/// until the closing bracket, and a bump arena cannot leave room for a count it has not got. The
/// nodes are dead as soon as the entries are copied into the cell, and the arena reclaims them
/// when the call that made them ends.
unsafe fn push(first: &mut u32, last: &mut u32, value: u32) {
    let node = alloc(8);
    write_u32(node as usize, value);
    write_u32(node as usize + 4, 0);
    if *first == 0 {
        *first = node;
    } else {
        write_u32(*last as usize + 4, node);
    }
    *last = node;
}

/// A header with room for nothing after it. Whoever wants a payload allocates it next, which the
/// arena puts immediately after — that is the whole of why a bump allocator is what holds these.
unsafe fn cell(tag: u32, length: u32) -> u32 {
    let out = alloc(HEADER as u32);
    write_u32(out as usize, tag);
    write_u32(out as usize + 4, length);
    out
}
