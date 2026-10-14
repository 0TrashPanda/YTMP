// First, before anything else runs: what older browsers (Chrome 90 on a Chromecast) lack.
import './lib/polyfills';
import type { HandleClientError } from '@sveltejs/kit/hooks';
import { reportProblem } from './lib/api';

// A page that crashes shows "500 Internal Error"; the host's log gets why (and in which
// browser), e.g. from a TV whose browser is too old for something.
export const handleError: HandleClientError = ({ kind, error }) => {
	if (kind === 'framework') return;
	const message = error instanceof Error ? `${error.name}: ${error.message}` : JSON.stringify(error);
	const where = error instanceof Error ? (error.stack ?? '').split('\n').slice(1, 3).join(' | ') : '';
	reportProblem(`page ${location.pathname} crashed: ${message} ${where} (${navigator.userAgent})`);
};
