(() => {
  if (document.getElementById('sixbridge-ondevice-gate')) return;
  const badge = document.createElement('div');
  badge.id = 'sixbridge-ondevice-gate';
  badge.textContent = 'SIX On-Device OK';
  badge.style.cssText = 'position:fixed;right:16px;bottom:96px;z-index:2147483647;background:#0b3aa8;color:#fff;padding:10px 14px;border-radius:20px;font:600 16px sans-serif;';
  document.documentElement.appendChild(badge);
  window.setTimeout(() => badge.remove(), 8000);
})();
