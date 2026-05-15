// @requires draftUseCases.js, draftWorkspace.js
// draftCommandQueue.js - serialize DraftWorkspace application commands.

function createDraftCommandQueue({ getWorkspace, setWorkspace, onStaleResponse = () => {}, onError = () => {} }) {
  let tail = Promise.resolve();
  let pendingCount = 0;

  function matchesMeta(workspace, meta) {
    if (meta.workspaceId && workspace?.workspaceId !== meta.workspaceId) return false;
    if (Number.isInteger(meta.expectedRevision) && workspace?.revision !== meta.expectedRevision) return false;
    return true;
  }

  function enqueue(meta, run) {
    const execute = async () => {
      pendingCount += 1;
      try {
        const before = getWorkspace();
        if (!matchesMeta(before, meta)) {
          onStaleResponse(meta);
          return { applied: false, reason: 'stale_before' };
        }
        const result = await run(before);
        const current = getWorkspace();
        if (!matchesMeta(current, meta)) {
          onStaleResponse(meta, result);
          return { applied: false, reason: 'stale_after', result };
        }
        if (result?.workspace) setWorkspace(result.workspace);
        return { applied: true, result };
      } catch (error) {
        onError(error, meta);
        throw error;
      } finally {
        pendingCount -= 1;
      }
    };
    const promise = tail.then(execute, execute);
    tail = promise.catch(() => {});
    return promise;
  }

  return {
    enqueue,
    isIdle: () => pendingCount === 0,
    pendingCount: () => pendingCount,
  };
}
