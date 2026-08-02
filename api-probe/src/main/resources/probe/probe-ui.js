const byId = (id) => document.getElementById(id);

const STATUS_IDS = Object.freeze({
  VERIFIED: 'count-ok',
  RESTRICTED: 'count-restricted',
  ABSENT: 'count-absent',
  FAILED: 'count-failed',
  NOT_RUN: 'count-not-run',
});

export function setProgress(current, total, context) {
  byId('progress-current').textContent = String(current);
  byId('progress-total').textContent = String(total);
  byId('current-context').textContent = `실행 context: ${String(context)}`;
}

export function setSummary(counts) {
  for (const [status, elementId] of Object.entries(STATUS_IDS)) {
    byId(elementId).textContent = String(counts?.[status] ?? 0);
  }
}

export function appendResult(result) {
  const item = document.createElement('li');
  const label = document.createElement('strong');
  label.textContent = `${String(result.namespace)}.${String(result.member)}`;
  const detail = document.createElement('span');
  detail.textContent = ` · ${String(result.status)} · ${String(result.context)}`;
  item.append(label, detail);
  byId('case-results').append(item);
}

export function setGesture(description, handler) {
  const region = byId('gesture');
  byId('gesture-description').textContent = String(description);
  const button = byId('gesture-run');
  button.onclick = handler;
  region.hidden = false;
}

export function setEvidenceStatus(status) {
  byId('evidence-status').textContent = `증거 전송: ${String(status)}`;
}
