(() => {
  const form = document.getElementById('organization-settings');
  if (!form) return;
  const hidden = document.getElementById('hidden');
  const update = () => {
    document.getElementById('hidden-help').hidden = !hidden.checked;
    for (const fieldset of form.querySelectorAll('[data-scope]')) {
      fieldset.disabled = hidden.checked;
      const allowlist = fieldset.querySelector('input[value="ALLOWLIST"]').checked;
      const recipients = fieldset.querySelector('[data-recipients]');
      recipients.hidden = !allowlist;
      for (const input of recipients.querySelectorAll('input')) input.disabled = hidden.checked || !allowlist;
    }
  };
  form.addEventListener('change', update);
  form.addEventListener('submit', () => {
    document.getElementById('save-settings').disabled = true;
    document.getElementById('save-status').textContent = 'Einstellungen werden gespeichert…';
    form.setAttribute('aria-busy', 'true');
  });
  // Browser history must fetch authorized state again instead of restoring a
  // stale directory or enabling an old form after an organization switch.
  window.addEventListener('pageshow', event => {
    if (event.persisted) window.location.reload();
  });
  update();
})();
