/*
 * Copied from rontolisp (https://github.com/making/rontolisp), Copyright Toshiaki Maki,
 * licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 and NOTICE.
 *
 * Changed from the original: the package name am.ik.wasm was rewritten to souther.wasm.emit,
 * wherever it appears. Nothing else was changed.
 */
package souther.wasm.emit;

/**
 * WASM limit types for memories and tables.
 */
public enum Limit implements Codable {

	/** Limit with minimum only. */
	MIN(0),
	/** Limit with minimum and maximum. */
	MINMAX(1);

	private final int code;

	Limit(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return this.code;
	}

}
