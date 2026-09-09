// Flat config. The repo carried `// eslint-disable-next-line
// react-hooks/exhaustive-deps` comments in four files with no ESLint installed
// anywhere, so the rule those suppressions name had never actually run. This is
// what makes them mean something.

import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,

      // `catch {}` with a comment explaining why is a deliberate pattern here
      // (localStorage in private mode, a dismissed share sheet); an empty block
      // with a comment in it is not the bug this rule is looking for.
      'no-empty': ['error', { allowEmptyCatch: true }],

      // TypeScript already reports unused values, with `noUnusedLocals` and
      // `noUnusedParameters` in tsconfig. Running it here too just double-reports.
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
);
