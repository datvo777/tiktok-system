import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { ApiError } from './api';
import { ErrorBoundary } from './ErrorBoundary';
import './styles.css';

const queryClient: QueryClient = new QueryClient({
  // `me` is read once on load and the session cookie lapses on its own (30 minutes, never renewed here), so
  // without this the console keeps showing the signed-in view over a queue that can only answer 401. Any other
  // query seeing a 401 means the session is gone: re-ask `me`, which then fails and returns to the sign-in form.
  queryCache: new QueryCache({
    onError: (error, query) => {
      if (error instanceof ApiError && error.status === 401 && query.queryKey[0] !== 'me') {
        void queryClient.resetQueries({ queryKey: ['me'] });
        void queryClient.resetQueries({ queryKey: ['mfa-status'] });
      }
    },
  }),
  defaultOptions: {
    queries: {
      // A 401 or 403 cannot be fixed by retrying — the credential is the
      // problem — and retrying turns one expired session into a burst of
      // failing requests.
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
        return failureCount < 2;
      },
    },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
    </QueryClientProvider>
  </React.StrictMode>,
);
