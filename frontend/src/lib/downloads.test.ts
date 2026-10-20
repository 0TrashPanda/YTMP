import { describe, expect, it } from 'vitest';
import { fileNameFrom } from './downloads';

describe('fileNameFrom', () => {
	it('takes the UTF-8 name first', () => {
		expect(fileNameFrom(`attachment; filename="Daft Punk - __n.m4a"; filename*=UTF-8''Daft%20Punk%20-%20%C3%89%C3%A9n.m4a`)).toBe('Daft Punk - Één.m4a');
	});
	it('falls back to the plain name', () => {
		expect(fileNameFrom('attachment; filename="Song.m4a"')).toBe('Song.m4a');
		expect(fileNameFrom(`attachment; filename="Song.m4a"; filename*=UTF-8''%E0%A4%A`)).toBe('Song.m4a');
		expect(fileNameFrom(null)).toBeNull();
	});
});
