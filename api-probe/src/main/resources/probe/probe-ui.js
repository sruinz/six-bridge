const byId = (id) => document.getElementById(id);

export function setProgress(current, total, context) {
  byId('progress-current').textContent = String(current);
  byId('progress-total').textContent = String(total);
  byId('current-context').textContent = `실행 context: ${String(context)}`;
}

export function summaryBuckets(counts) {
  return {
    ok: Number(counts?.VERIFIED ?? 0) + Number(counts?.EXPOSED ?? 0),
    restricted: Number(counts?.RESTRICTED ?? 0),
    absent: Number(counts?.ABSENT ?? 0),
    failed: Number(counts?.FAILED ?? 0),
    notRun: Number(counts?.NOT_RUN ?? 0),
  };
}

export function setSummary(counts) {
  const buckets = summaryBuckets(counts);
  byId('count-ok').textContent = String(buckets.ok);
  byId('count-restricted').textContent = String(buckets.restricted);
  byId('count-absent').textContent = String(buckets.absent);
  byId('count-failed').textContent = String(buckets.failed);
  byId('count-not-run').textContent = String(buckets.notRun);
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
