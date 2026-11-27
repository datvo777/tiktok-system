import { createContext, useContext } from 'react';

/**
 * Who is watching, and how to ask them to sign in.
 *
 * <p>Most of the app is now readable signed out — the feed, a shared video, a
 * creator page, search — while every action that writes still needs an account.
 * That distinction shows up in a dozen components, and threading both a nullable
 * viewer id and a "prompt them" callback through all of them by prop would be
 * noise in every signature between here and there.
 *
 * <p>{@link useViewer} returns the id or null. {@link useRequireAccount} returns
 * a wrapper that runs an action if there is an account and opens the sign-in
 * sheet if there is not — so a call site reads as the action it performs, not as
 * a conditional around it.
 */
export type ViewerContextValue = {
  /** Null when signed out. */
  viewerId: string | null;
  promptSignIn: () => void;
};

const ViewerContext = createContext<ViewerContextValue>({
  viewerId: null,
  promptSignIn: () => {},
});

export const ViewerProvider = ViewerContext.Provider;

export function useViewer(): ViewerContextValue {
  return useContext(ViewerContext);
}

/**
 * Wraps an action so it only runs for a signed-in viewer.
 *
 * <p>Returns a function rather than a boolean so the guard cannot be forgotten
 * at the call site: `onClick={guard(() => like.mutate())}` either likes or asks
 * them to sign in, and there is no third path where it silently does nothing.
 */
export function useRequireAccount(): (action: () => void) => () => void {
  const { viewerId, promptSignIn } = useViewer();
  return (action: () => void) => () => {
    if (viewerId) action();
    else promptSignIn();
  };
}
