/// <reference types="vite/client" />
//
// Brings in Vite's `import.meta.env` typings. Without it `import.meta.env.DEV`
// -- which gates the development-only credential prefills -- is a type error
// even though Vite substitutes it at build time.
