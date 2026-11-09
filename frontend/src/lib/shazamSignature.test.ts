import { describe, expect, it } from 'vitest';
import { encode, peaks, signature } from './shazamSignature';

// The test sound: 4 s, a chord that changes every half second, with some fixed noise.
function testSound(): Int16Array {
	const out = new Int16Array(4 * 16000);
	let seed = 12345;
	const chords = [[262, 330, 392], [294, 370, 440], [330, 415, 494], [349, 440, 523], [392, 494, 587], [440, 554, 659], [494, 622, 740], [523, 659, 784]];
	for (let i = 0; i < out.length; i++) {
		const t = i / 16000;
		const chord = chords[Math.floor(t * 2) % chords.length];
		seed = (seed * 1103515245 + 12345) % 2147483648;
		let v = (seed / 2147483648 - 0.5) * 0.05;
		for (const [k, hz] of chord.entries()) v += 0.2 * Math.sin(2 * Math.PI * hz * t) + 0.08 * Math.sin(2 * Math.PI * hz * (k + 2) * 2 * t);
		out[i] = Math.trunc(v * 32767);
	}
	return out;
}

// shazamio-core 2.0.0's own fingerprint code (algorithm.rs, signature_format.rs, built on its
// own without its resampler) for testSound().
const SHAZAMIO = 'data:audio/vnd.shazam.sig;base64,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';

/** The peaks in a signature: [band, pass, magnitude, bin]. */
function decode(uri: string): number[][] {
	const bytes = Uint8Array.from(atob(uri.split(',')[1]), (c) => c.charCodeAt(0));
	const view = new DataView(bytes.buffer);
	const out: number[][] = [];
	let at = 56;
	while (at < bytes.length) {
		const band = view.getUint32(at, true) - 0x60030040;
		const size = view.getUint32(at + 4, true);
		let p = at + 8;
		let pass = 0;
		while (p < at + 8 + size) {
			if (bytes[p] === 0xff) {
				pass = view.getUint32(p + 1, true);
				p += 5;
			}
			pass += bytes[p];
			out.push([band, pass, view.getUint16(p + 1, true), view.getUint16(p + 3, true)]);
			p += 5;
		}
		at += 8 + size + ((4 - (size % 4)) % 4);
	}
	return out;
}

describe("Shazam's signature", () => {
	it('has the peaks shazamio finds: the same times, bands and frequencies, loudness within 1', () => {
		const mine = decode(signature(testSound()).uri);
		const theirs = decode(SHAZAMIO);
		expect(mine.length).toBe(theirs.length);
		let off = 0;
		mine.forEach(([band, pass, magnitude, bin], i) => {
			const [b, p, m, f] = theirs[i];
			expect([band, pass, bin]).toEqual([b, p, f]);
			expect(Math.abs(magnitude - m)).toBeLessThanOrEqual(1);
			if (magnitude !== m) off++;
		});
		// The last bit of a float in the FFT (doubles here, floats there): a handful.
		expect(off).toBeLessThan(mine.length / 50);
	});

	it('is in Shazam\'s format: header, sizes and checksum like shazamio\'s', () => {
		const bytes = encode(peaks(testSound()), 64000);
		const view = new DataView(bytes.buffer);
		expect(view.getUint32(0, true)).toBe(0xcafe2580);
		expect(view.getUint32(8, true)).toBe(bytes.length - 48);
		expect(view.getUint32(12, true)).toBe(0x94119c00);
		expect(view.getUint32(28, true)).toBe(3 << 27);
		expect(view.getUint32(40, true)).toBe(64000 + 3840);
		// Same header as shazamio's apart from the checksum (the peaks differ a little).
		const theirs = Uint8Array.from(atob(SHAZAMIO.split(',')[1]), (c) => c.charCodeAt(0));
		expect(Array.from(bytes.slice(8, 56))).toEqual(Array.from(theirs.slice(8, 56)));
	});

	it('says how long the sound was, and keeps only the middle 10 seconds of longer sound', () => {
		expect(signature(testSound()).sampleMs).toBe(4000);
		const long = new Int16Array(16 * 16000);
		long.set(testSound(), 6 * 16000);
		expect(signature(long).sampleMs).toBe(10000);
		expect(decode(signature(long).uri).length).toBeGreaterThan(0);
	});

	it('silence has no peaks', () => {
		expect(peaks(new Int16Array(3 * 16000)).flat()).toEqual([]);
	});
});
