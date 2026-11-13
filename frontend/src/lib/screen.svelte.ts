// Whether the window is as wide as Tailwind's `sm:` (the layout with the left side), for
// components whose markup differs between phones and computers, not only their classes.
const query = typeof matchMedia === 'function' ? matchMedia('(min-width: 640px)') : null;

export const screen = $state({ wide: query?.matches ?? false });

query?.addEventListener('change', (e) => (screen.wide = e.matches));
