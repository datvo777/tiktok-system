import { useSyncExternalStore } from 'react';
import { readRoute, subscribe, toPath, type Route } from './router';

/**
 * The current route, re-rendering on Back/Forward and on our own pushes.
 *
 * <p>`useSyncExternalStore` rather than `useState` + an effect: the route lives
 * in the History API, which is genuinely external state, and this is the hook
 * that exists for reading it without tearing during a concurrent render.
 *
 * <p>The snapshot has to be referentially stable between changes or React
 * re-renders forever, and {@link readRoute} builds a fresh object every call —
 * so the parsed route is memoised against its own serialised path, which is
 * exactly the identity we care about.
 */
let cachedPath: string | null = null;
let cachedRoute: Route | null = null;

function snapshot(): Route {
  const route = readRoute();
  const path = toPath(route);
  if (path !== cachedPath || cachedRoute === null) {
    cachedPath = path;
    cachedRoute = route;
  }
  return cachedRoute;
}

export function useRoute(): Route {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
