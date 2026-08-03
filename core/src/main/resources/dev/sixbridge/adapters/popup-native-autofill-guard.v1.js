(() => {
  'use strict';
  const protectedTypes = new Set(['text', 'email', 'password', 'url', 'tel', 'search']);
  const protect = (node) => {
    if (!(node instanceof HTMLInputElement)) return;
    const type = (node.getAttribute('type') || 'text').toLowerCase();
    if (protectedTypes.has(type)) node.setAttribute('autocomplete', 'off');
  };
  const scan = (root) => {
    if (root instanceof HTMLInputElement) protect(root);
    root.querySelectorAll?.('input').forEach(protect);
  };
  scan(document);
  new MutationObserver((records) => {
    records.forEach((record) => record.addedNodes.forEach(scan));
  }).observe(document, { childList: true, subtree: true });
  addEventListener('focusin', (event) => protect(event.target), true);
})();
