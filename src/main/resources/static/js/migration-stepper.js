function goToStep(step, scroll=true) {
  const m = SchemaBridgeState.migration;
  if (step > m.maxStep || step < 1) return;
  m.currentStep = step;
  document.querySelectorAll('[data-step-page]').forEach(page => page.hidden = Number(page.dataset.stepPage) !== step);
  document.querySelectorAll('[data-step-nav]').forEach(button => {
    const number = Number(button.dataset.stepNav), active = number === step;
    button.classList.toggle('active', active); button.classList.toggle('complete', number < step);
    button.disabled = number > m.maxStep;
    if (active) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');
  });
  if (scroll) window.scrollTo({top:0,behavior:'smooth'});
}
