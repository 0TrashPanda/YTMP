// Shazam's audio fingerprint ("signature") of a few seconds of sound, made on the device, so any
// host can recognize a song (docs/implementation/module-api.md#recognize-a-song): only this small
// signature goes to the host, which asks Shazam.
//
// A port of shazamio-core 2.0.0 (MIT, © 2024 dotX12; src/fingerprinting/algorithm.rs and
// signature_format.rs), itself a port of SongRec by Marin M. Every 128 samples (16 kHz mono) a
// 2048-sample Hann-windowed FFT; the spectrum is spread over neighbouring bins and frames, and the
// local maxima between 250 Hz and 5.5 kHz become peaks, packed per frequency band. The arithmetic
// is 32-bit like the original (Math.fround), so the peaks come out the same as shazamio's.
// Pure functions without the DOM, so the tests check it against shazamio's own output.

/** Shazam's sample rate. */
export const SIGNATURE_RATE = 16000;
/** Longer sound is cut to its middle 10 seconds, as shazamio does. */
export const MAX_SECONDS = 10;

const f = Math.fround;

/** shazamio's Hann window: the formula, rounded to 5 significant digits like its table. */
const HANN = Float32Array.from({ length: 2048 }, (_, i) => Number((0.5 - 0.5 * Math.cos((2 * Math.PI * (i + 1)) / 2049)).toPrecision(5)));

// A 2048-point FFT (radix 2, in place), in doubles: closer to the exact transform than the
// original's f32, which only matters in the last bit of a few magnitudes.
const N = 2048;
const COS = Float64Array.from({ length: N / 2 }, (_, i) => Math.cos((-2 * Math.PI * i) / N));
const SIN = Float64Array.from({ length: N / 2 }, (_, i) => Math.sin((-2 * Math.PI * i) / N));
const REVERSED = Uint16Array.from({ length: N }, (_, i) => {
	let r = 0;
	for (let b = 0, x = i; b < 11; b++, x >>= 1) r = (r << 1) | (x & 1);
	return r;
});

function fft(re: Float64Array, im: Float64Array): void {
	for (let i = 0; i < N; i++) {
		const j = REVERSED[i];
		if (j > i) {
			[re[i], re[j]] = [re[j], re[i]];
			[im[i], im[j]] = [im[j], im[i]];
		}
	}
	for (let size = 2; size <= N; size <<= 1) {
		const half = size >> 1;
		const step = N / size;
		for (let start = 0; start < N; start += size) {
			for (let k = 0; k < half; k++) {
				const c = COS[k * step];
				const s = SIN[k * step];
				const a = start + k;
				const b = a + half;
				const tr = re[b] * c - im[b] * s;
				const ti = re[b] * s + im[b] * c;
				re[b] = re[a] - tr;
				im[b] = im[a] - ti;
				re[a] += tr;
				im[a] += ti;
			}
		}
	}
}

/** One peak: when (in 128-sample steps), how loud (log scale) and which frequency (bin × 64). */
export interface Peak {
	pass: number;
	magnitude: number;
	bin: number;
}

/** The peaks of 16 kHz mono [samples], per frequency band (0: 250–520 Hz, 1: –1450, 2: –3500, 3: –5500). */
export function peaks(samples: Int16Array): Peak[][] {
	const bands: Peak[][] = [[], [], [], []];
	const ring = new Int16Array(2048);
	let ringIndex = 0;
	const ffts = Array.from({ length: 256 }, () => new Float32Array(1025));
	let fftIndex = 0;
	const spread = Array.from({ length: 256 }, () => new Float32Array(1025));
	let spreadIndex = 0;
	let done = 0;
	const re = new Float64Array(N);
	const im = new Float64Array(N);
	const ln = (x: number) => f(Math.max(f(Math.log(x)), f(1 / 64)));
	const magnitude = (x: number) => f(f(ln(x) * f(1477.3)) + 6144);

	for (let chunk = 0; chunk + 128 <= samples.length; chunk += 128) {
		// The FFT of the last 2048 samples, windowed.
		ring.set(samples.subarray(chunk, chunk + 128), ringIndex);
		ringIndex = (ringIndex + 128) & 2047;
		for (let i = 0; i < N; i++) {
			re[i] = f(ring[(i + ringIndex) & 2047] * HANN[i]);
			im[i] = 0;
		}
		fft(re, im);
		const out = ffts[fftIndex];
		for (let i = 0; i <= 1024; i++) {
			const rf = f(re[i]);
			const imf = f(im[i]);
			out[i] = Math.max(f(f(f(rf * rf) + f(imf * imf)) / 131072), f(0.0000000001));
		}
		fftIndex = (fftIndex + 1) & 255;

		// Spreading: each bin the most of itself and the next two, and pushed into 3 earlier frames.
		const s = spread[spreadIndex];
		s.set(out);
		for (let p = 0; p <= 1022; p++) s[p] = Math.max(s[p], s[p + 1], s[p + 2]);
		for (let p = 0; p <= 1024; p++) {
			for (const back of [1, 3, 6]) {
				const former = spread[(spreadIndex - back) & 255];
				former[p] = Math.max(former[p], s[p]);
			}
		}
		spreadIndex = (spreadIndex + 1) & 255;
		done++;
		if (done < 46) continue;

		// Peaks of the frame 46 back: louder than its neighbours in frequency and in time.
		const at = ffts[(fftIndex - 46) & 255];
		const near = spread[(spreadIndex - 49) & 255];
		for (let bin = 10; bin <= 1014; bin++) {
			if (!(at[bin] >= f(1 / 64) && at[bin] >= near[bin - 1])) continue;
			let most = 0;
			for (const o of [-10, -7, -4, -3, 1, 2, 5, 8]) most = Math.max(most, near[bin + o]);
			if (!(at[bin] > most)) continue;
			for (const o of [-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249]) most = Math.max(most, spread[(spreadIndex + o) & 255][bin - 1]);
			if (!(at[bin] > most)) continue;

			const m = magnitude(at[bin]);
			const before = magnitude(at[bin - 1]);
			const after = magnitude(at[bin + 1]);
			const variation1 = f(f(f(m * 2) - before) - after);
			const variation2 = f(f(f(after - before) * 32) / variation1);
			// Rust's `as i32` truncates (and NaN is 0); `as u16` of an i32 wraps.
			const corrected = (bin * 64 + (Number.isNaN(variation2) ? 0 : Math.trunc(variation2))) & 0xffff;
			const hz = Math.trunc(f(corrected * f(16000 / 2 / 1024 / 64)));
			const band = hz >= 250 && hz <= 519 ? 0 : hz >= 520 && hz <= 1449 ? 1 : hz >= 1450 && hz <= 3499 ? 2 : hz >= 3500 && hz <= 5500 ? 3 : -1;
			if (band < 0) continue;
			bands[band].push({ pass: done - 46, magnitude: Math.min(65535, Math.max(0, Math.trunc(m))), bin: corrected });
		}
	}
	return bands;
}

const CRC = Uint32Array.from({ length: 256 }, (_, n) => {
	let c = n;
	for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	return c >>> 0;
});

function crc32(bytes: Uint8Array): number {
	let c = 0xffffffff;
	for (const b of bytes) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
}

/** The signature in Shazam's binary format. */
export function encode(bands: Peak[][], sampleCount: number): Uint8Array {
	const parts: number[] = [];
	const u8 = (v: number) => parts.push(v & 0xff);
	const u16 = (v: number) => (u8(v), u8(v >>> 8));
	const u32 = (v: number) => (u16(v & 0xffff), u16(v >>> 16));
	u32(0xcafe2580);
	u32(0); // crc32, below
	u32(0); // size minus header, below
	u32(0x94119c00);
	u32(0);
	u32(0);
	u32(0);
	u32(3 << 27); // 16 kHz
	u32(0);
	u32(0);
	u32(sampleCount + Math.trunc(f(SIGNATURE_RATE * f(0.24))));
	u32((15 << 19) + 0x40000);
	u32(0x40000000);
	u32(0); // size minus header, below
	bands.forEach((peaksOfBand, band) => {
		if (!peaksOfBand.length) return;
		const buffer: number[] = [];
		const b8 = (v: number) => buffer.push(v & 0xff);
		let pass = 0;
		for (const peak of peaksOfBand) {
			if (peak.pass - pass >= 255) {
				b8(0xff);
				for (let i = 0; i < 4; i++) b8(peak.pass >>> (8 * i));
				pass = peak.pass;
			}
			b8(peak.pass - pass);
			b8(peak.magnitude);
			b8(peak.magnitude >>> 8);
			b8(peak.bin);
			b8(peak.bin >>> 8);
			pass = peak.pass;
		}
		u32(0x60030040 + band);
		u32(buffer.length);
		parts.push(...buffer);
		for (let i = 0; i < (4 - (buffer.length % 4)) % 4; i++) u8(0);
	});
	const bytes = Uint8Array.from(parts);
	const view = new DataView(bytes.buffer);
	view.setUint32(8, bytes.length - 48, true);
	view.setUint32(52, bytes.length - 48, true);
	view.setUint32(4, crc32(bytes.subarray(8)), true);
	return bytes;
}

/** What goes to the host: the signature as Shazam's data URI, and how long the sound was. */
export interface Signature {
	uri: string;
	sampleMs: number;
}

/** The signature of 16 kHz mono [samples] (its middle 10 seconds when longer, like shazamio). */
export function signature(samples: Int16Array): Signature {
	const max = MAX_SECONDS * SIGNATURE_RATE;
	const middle = samples.length <= max ? samples : samples.subarray((samples.length >> 1) - (max >> 1), (samples.length >> 1) + (max >> 1));
	const bytes = encode(peaks(middle), middle.length);
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return { uri: `data:audio/vnd.shazam.sig;base64,${btoa(binary)}`, sampleMs: Math.trunc(f(f(middle.length / SIGNATURE_RATE) * 1000)) };
}
