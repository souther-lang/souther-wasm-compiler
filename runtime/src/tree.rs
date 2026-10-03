//! The members of a set, or the entries of a map, as a tree that a change copies a path of.
//!
//! A set or a map that a body changes one member at a time is changed by making a new one, and the
//! one it was made from is still there for whoever holds it. Held as an array in order, each change
//! copies every member, so a walk that grows one takes room and time that grow with the square of
//! how many it ends with — the JVM's collections are persistent and take neither. Held as this
//! tree, a change makes a new node for each one on the path to where it lands and shares the rest,
//! which is as many as the tree is deep.
//!
//! Balanced by weight as `Data.Map` balances (Adams; Straka's parameters, three and two): each side
//! of a node holds at most three times as many as the other, so the tree is as deep as the
//! logarithm of how many it holds, and one member put in or taken out is put right by one or two
//! rotations at each node on the path.
//!
//! ```text
//! +0  u32 the key, or the member
//! +4  u32 the value, or nothing for a set's member
//! +8  u32 the subtree of what stands before it, or nothing
//! +12 u32 the subtree of what stands after it, or nothing
//! +16 u32 how many the subtree holds, itself included
//! ```
//!
//! A tree is no cell: a set or a map cell points at one, and lays it out as the array its readers
//! read the first time one of them asks (`value`).

use crate::alloc;
use crate::order;
use crate::value;

const NODE: u32 = 20;
const DELTA: u32 = 3;
const RATIO: u32 = 2;

/// What a tree's members stand in the order of.
#[derive(Clone, Copy)]
pub enum Order {
    /// A set's members, in the order of the element type its descriptor names.
    Members(u32),
    /// A map's keys, in the order their written forms stand in, as a map's keys do.
    Keys(u32),
}

unsafe fn compared(order: Order, left: u32, right: u32) -> i32 {
    match order {
        Order::Members(element) => order::compare(left, right, element),
        Order::Keys(keys) => value::key_order(left, right, keys),
    }
}

unsafe fn read(at: u32) -> u32 {
    core::ptr::read_unaligned(at as usize as *const u32)
}

unsafe fn write(at: u32, word: u32) {
    core::ptr::write_unaligned(at as usize as *mut u32, word);
}

pub unsafe fn value_of(node: u32) -> u32 {
    read(node + 4)
}

unsafe fn left(node: u32) -> u32 {
    read(node + 8)
}

unsafe fn right(node: u32) -> u32 {
    read(node + 12)
}

/// How many a tree holds.
pub unsafe fn size(node: u32) -> u32 {
    if node == 0 {
        0
    } else {
        read(node + 16)
    }
}

unsafe fn node(key: u32, held: u32, before: u32, after: u32) -> u32 {
    let at = alloc(NODE);
    write(at, key);
    write(at + 4, held);
    write(at + 8, before);
    write(at + 12, after);
    write(at + 16, size(before) + size(after) + 1);
    at
}

/// The node holding `key`, or nothing.
pub unsafe fn found(tree: u32, key: u32, order: Order) -> u32 {
    let mut at = tree;
    while at != 0 {
        let held = compared(order, key, read(at));
        if held == 0 {
            return at;
        }
        at = if held < 0 { left(at) } else { right(at) };
    }
    0
}

/// The tree with `key` in it standing over `held`.
///
/// Where the tree holds the key already, a map's entry keeps the key it was first put in under and
/// stands over the new value (`replacing`), and a set's member is left as it is: the tree answered
/// is then the one given, so a caller can tell that nothing changed and hand back what it had.
pub unsafe fn inserted(tree: u32, key: u32, held: u32, order: Order, replacing: bool) -> u32 {
    if tree == 0 {
        return node(key, held, 0, 0);
    }
    let at = compared(order, key, read(tree));
    if at < 0 {
        let before = inserted(left(tree), key, held, order, replacing);
        if before == left(tree) {
            return tree;
        }
        balanced(read(tree), value_of(tree), before, right(tree))
    } else if at > 0 {
        let after = inserted(right(tree), key, held, order, replacing);
        if after == right(tree) {
            return tree;
        }
        balanced(read(tree), value_of(tree), left(tree), after)
    } else if replacing {
        node(read(tree), held, left(tree), right(tree))
    } else {
        tree
    }
}

/// The tree without `key`, or the tree given where it does not hold it.
pub unsafe fn removed(tree: u32, key: u32, order: Order) -> u32 {
    if tree == 0 {
        return 0;
    }
    let at = compared(order, key, read(tree));
    if at < 0 {
        let before = removed(left(tree), key, order);
        if before == left(tree) {
            return tree;
        }
        balanced(read(tree), value_of(tree), before, right(tree))
    } else if at > 0 {
        let after = removed(right(tree), key, order);
        if after == right(tree) {
            return tree;
        }
        balanced(read(tree), value_of(tree), left(tree), after)
    } else {
        glued(left(tree), right(tree))
    }
}

/// Two trees whose members all stand in order, the left's before the right's, as one: the nearest
/// member of the larger is lifted to stand between them.
unsafe fn glued(before: u32, after: u32) -> u32 {
    if before == 0 {
        return after;
    }
    if after == 0 {
        return before;
    }
    if size(before) > size(after) {
        let (key, held, rest) = without_last(before);
        balanced(key, held, rest, after)
    } else {
        let (key, held, rest) = without_first(after);
        balanced(key, held, before, rest)
    }
}

unsafe fn without_first(tree: u32) -> (u32, u32, u32) {
    if left(tree) == 0 {
        return (read(tree), value_of(tree), right(tree));
    }
    let (key, held, rest) = without_first(left(tree));
    (key, held, balanced(read(tree), value_of(tree), rest, right(tree)))
}

unsafe fn without_last(tree: u32) -> (u32, u32, u32) {
    if right(tree) == 0 {
        return (read(tree), value_of(tree), left(tree));
    }
    let (key, held, rest) = without_last(right(tree));
    (key, held, balanced(read(tree), value_of(tree), left(tree), rest))
}

/// A node over two subtrees that were balanced before one member went into or out of one of them,
/// balanced again.
unsafe fn balanced(key: u32, held: u32, before: u32, after: u32) -> u32 {
    let (b, a) = (size(before), size(after));
    if b + a <= 1 {
        node(key, held, before, after)
    } else if a > DELTA * b {
        if size(left(after)) < RATIO * size(right(after)) {
            // Single: the right child rises.
            node(
                read(after),
                value_of(after),
                node(key, held, before, left(after)),
                right(after),
            )
        } else {
            // Double: the right child's left child rises.
            let middle = left(after);
            node(
                read(middle),
                value_of(middle),
                node(key, held, before, left(middle)),
                node(read(after), value_of(after), right(middle), right(after)),
            )
        }
    } else if b > DELTA * a {
        if size(right(before)) < RATIO * size(left(before)) {
            node(
                read(before),
                value_of(before),
                left(before),
                node(key, held, right(before), after),
            )
        } else {
            let middle = right(before);
            node(
                read(middle),
                value_of(middle),
                node(read(before), value_of(before), left(before), left(middle)),
                node(key, held, right(middle), after),
            )
        }
    } else {
        node(key, held, before, after)
    }
}

/// A balanced tree of what an array in order holds from `from` up to `to`: its keys `stride` bytes
/// apart, each with its value in the word after it where `valued`.
pub unsafe fn of_ordered(at: u32, from: u32, to: u32, stride: u32, valued: bool) -> u32 {
    if from >= to {
        return 0;
    }
    let middle = from + (to - from) / 2;
    let entry = at + middle * stride;
    node(
        read(entry),
        if valued { read(entry + 4) } else { 0 },
        of_ordered(at, from, middle, stride, valued),
        of_ordered(at, middle + 1, to, stride, valued),
    )
}

/// Lays a tree out in order into an array: each key `stride` bytes after the last, with its value
/// in the word after it where `valued`. Answers where the next one would go.
pub unsafe fn laid_out(tree: u32, into: u32, stride: u32, valued: bool) -> u32 {
    if tree == 0 {
        return into;
    }
    let at = laid_out(left(tree), into, stride, valued);
    write(at, read(tree));
    if valued {
        write(at + 4, value_of(tree));
    }
    laid_out(right(tree), at + stride, stride, valued)
}
