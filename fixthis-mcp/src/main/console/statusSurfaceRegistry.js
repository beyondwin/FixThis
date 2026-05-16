// @requires (none)
// statusSurfaceRegistry.js - central coordinator for console status surfaces.

const StatusSurfaceStackLimit = Object.freeze({
  modal: 1,
  modalCanvas: 1,
  banner: 1,
  inline: Infinity,
  badge: Infinity,
  toast: 3,
  pill: 1,
});

const ModalSurfaceClasses = Object.freeze(new Set(['modal', 'modalCanvas']));
const ModalSuspendedSurfaceClasses = Object.freeze(['inline', 'badge']);

function createStatusSurfaceRegistry({ document: document_ = document } = {}) {
  const surfaces = new Map();
  const visibleByClass = new Map();
  const suspendedClasses = new Set();

  function trackVisible(id, surfaceClass) {
    if (!visibleByClass.has(surfaceClass)) visibleByClass.set(surfaceClass, new Set());
    visibleByClass.get(surfaceClass).add(id);
  }

  function untrackVisible(id, surfaceClass) {
    visibleByClass.get(surfaceClass)?.delete(id);
  }

  function idsForClass(surfaceClass) {
    return Array.from(visibleByClass.get(surfaceClass) || []);
  }

  function size(surfaceClass) {
    return visibleByClass.get(surfaceClass)?.size ?? 0;
  }

  function applyContent(element, content) {
    if (!element || typeof content === 'undefined') return;
    if (typeof content === 'string') {
      element.textContent = content;
      return;
    }
    if (!content || typeof content !== 'object') return;

    const headline = element.querySelector?.('[data-headline]');
    if (headline && typeof content.headline === 'string') headline.textContent = content.headline;
    const detail = element.querySelector?.('[data-detail]');
    if (detail && typeof content.detail === 'string') detail.textContent = content.detail;
    const retry = element.querySelector?.('[data-retry]');
    if (retry && typeof content.retry === 'boolean') retry.hidden = !content.retry;
  }

  function renderToast(id, content) {
    const container = document_.getElementById('toastContainer');
    if (!container) return null;
    const element = document_.createElement('div');
    element.className = 'toast';
    element.dataset.toastId = id;
    element.setAttribute?.('role', 'status');
    element.textContent = typeof content === 'string' ? content : '';
    container.appendChild(element);
    return element;
  }

  function suspend(surfaceClass) {
    suspendedClasses.add(surfaceClass);
    for (const id of idsForClass(surfaceClass)) {
      const entry = surfaces.get(id);
      if (entry?.element) entry.element.hidden = true;
    }
  }

  function resume(surfaceClass) {
    suspendedClasses.delete(surfaceClass);
    for (const id of idsForClass(surfaceClass)) {
      const entry = surfaces.get(id);
      if (entry?.element) entry.element.hidden = false;
    }
  }

  function hide(id) {
    const entry = surfaces.get(id);
    if (!entry) return;
    if (entry.timer) clearTimeout(entry.timer);
    if (entry.element) {
      if (entry.surfaceClass === 'toast' && entry.generatedElement) entry.element.remove();
      else entry.element.hidden = true;
    }
    surfaces.delete(id);
    untrackVisible(id, entry.surfaceClass);

    if (
      ModalSurfaceClasses.has(entry.surfaceClass) &&
      size('modal') === 0 &&
      size('modalCanvas') === 0
    ) {
      ModalSuspendedSurfaceClasses.forEach(resume);
    }
  }

  function hideModalSurfacesExcept(id) {
    for (const surfaceClass of ModalSurfaceClasses) {
      for (const visibleId of idsForClass(surfaceClass)) {
        if (visibleId !== id) hide(visibleId);
      }
    }
  }

  function show(id, opts = {}) {
    if (!id) return;
    if (surfaces.has(id)) hide(id);

    const surfaceClass = opts.surfaceClass;
    if (!surfaceClass) return;

    if (ModalSurfaceClasses.has(surfaceClass)) {
      hideModalSurfacesExcept(id);
      ModalSuspendedSurfaceClasses.forEach(suspend);
    }

    const limit = StatusSurfaceStackLimit[surfaceClass] ?? Infinity;
    while (size(surfaceClass) >= limit) {
      const oldest = idsForClass(surfaceClass)[0];
      if (!oldest) break;
      hide(oldest);
    }

    const generatedElement = !opts.element && surfaceClass === 'toast';
    const element = opts.element || (generatedElement ? renderToast(id, opts.content) : null);
    surfaces.set(id, { ...opts, element, generatedElement, surfaceClass });
    trackVisible(id, surfaceClass);

    if (element) {
      applyContent(element, opts.content);
      element.hidden = suspendedClasses.has(surfaceClass);
    }

    if (opts.autoDismissMs && opts.autoDismissMs > 0) {
      const timer = setTimeout(() => hide(id), opts.autoDismissMs);
      timer.unref?.();
      surfaces.get(id).timer = timer;
    }
  }

  return {
    hide,
    resume,
    show,
    size,
    suspend,
  };
}
