/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * Interface for WASM elements that have an integer code representation.
 */
@FunctionalInterface
public interface Codable {

	/**
	 * Return the integer code for this element.
	 * @return the code value
	 */
	int code();

}
