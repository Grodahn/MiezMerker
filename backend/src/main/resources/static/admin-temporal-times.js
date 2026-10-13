document.querySelectorAll('time[data-utc-ms], time[data-utc]').forEach(element => {
  const value = element.dataset.utcMs ? Number(element.dataset.utcMs) : Date.parse(element.dataset.utc);
  const date = new Date(value);
  if (value > 0 && Number.isSafeInteger(value) && !Number.isNaN(date.getTime())) {
    element.dateTime = date.toISOString();
    element.textContent = new Intl.DateTimeFormat('de-DE', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3, timeZoneName: 'short' }).format(date);
  }
});
