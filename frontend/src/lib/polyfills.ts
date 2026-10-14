// Older Chromecasts run Chrome 90 (a TV showing the party screen): the few newer built-ins the
// app and its libraries use. Each is only added where it's missing.

function define<T extends object>(target: T, name: string, value: unknown) {
	if (!(name in target)) Object.defineProperty(target, name, { value, writable: true, configurable: true, enumerable: false });
}

function at(this: ArrayLike<unknown>, index: number) {
	const i = Math.trunc(index) || 0;
	const k = i < 0 ? this.length + i : i;
	return k >= 0 && k < this.length ? this[k] : undefined;
}
for (const proto of [Array.prototype, String.prototype, Object.getPrototypeOf(Int8Array.prototype)]) define(proto, 'at', at);

define(Object, 'hasOwn', (object: object, key: PropertyKey) => Object.prototype.hasOwnProperty.call(object, key));

define(Array.prototype, 'findLast', function <T>(this: T[], test: (value: T, index: number, array: T[]) => unknown) {
	for (let i = this.length - 1; i >= 0; i--) if (test(this[i], i, this)) return this[i];
	return undefined;
});
define(Array.prototype, 'findLastIndex', function <T>(this: T[], test: (value: T, index: number, array: T[]) => unknown) {
	for (let i = this.length - 1; i >= 0; i--) if (test(this[i], i, this)) return i;
	return -1;
});

if (typeof AbortSignal !== 'undefined') {
	define(AbortSignal, 'timeout', (ms: number) => {
		const controller = new AbortController();
		setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms);
		return controller.signal;
	});
}
