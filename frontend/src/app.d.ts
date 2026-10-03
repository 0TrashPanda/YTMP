// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		interface PageState {
			/** The phone search page is open (so the back button closes it). */
			searching?: boolean;
			/** The full player is open (so the back button closes it). */
			player?: boolean;
		}
		// interface Platform {}
	}
}

export {};
