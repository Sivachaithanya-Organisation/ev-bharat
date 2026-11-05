(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const api = (p) => fetch('api/' + p).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmt = (n) => Number(n).toLocaleString('en-IN');
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const fail = (sel, e) => { $(sel).innerHTML = `<p class="err">Could not load data (${esc(e.message)}). Is the API running?</p>`; };

  const tabs = ['map', 'cars', 'bikes', 'policy', 'culture'];
  const loaded = {};
  let map, layer, userMarker, userPos = null;

  /* ---------- Finder ---------- */
  function initMap() {
    map = L.map('leaflet').setView([22.5, 79], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18, attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);
    layer = L.layerGroup().addTo(map);

    api('stats').then((s) => {
      $('#stats').innerHTML = [
        [s.stations, 'stations'], [s.highway, 'on highways'], [s.states, 'states and UTs'], [s.fastDc, 'fast (50 kW+)']
      ].map(([n, l]) => `<div><b>${fmt(n)}</b>${l}</div>`).join('');
    }).catch((e) => fail('#stats', e));

    api('stations/meta').then((m) => {
      $('select[name=state]').insertAdjacentHTML('beforeend', m.states.map((s) => `<option>${esc(s)}</option>`).join(''));
      $('select[name=connector]').insertAdjacentHTML('beforeend', m.connectors.map((c) => `<option>${esc(c)}</option>`).join(''));
    }).catch(() => {}).finally(loadStations);

    const form = $('#filters');
    form.addEventListener('input', debounce(loadStations, 200));
    form.addEventListener('submit', (e) => e.preventDefault());
    $('#near').addEventListener('click', toggleNearMe);
  }

  function loadStations() {
    const p = new URLSearchParams(new FormData($('#filters')));
    [...p.keys()].forEach((k) => { if (!p.get(k)) p.delete(k); });
    if (userPos) { p.set('lat', userPos[0]); p.set('lng', userPos[1]); } else { p.delete('radiusKm'); }
    api('stations?' + p).then(renderStations).catch((e) => fail('#list', e));
  }

  function renderStations(rows) {
    layer.clearLayers();
    const list = $('#list');
    list.innerHTML = '';
    $('#count').textContent = rows.length === 1 ? '1 station' : rows.length + ' stations';
    const points = [];
    rows.forEach((s) => {
      points.push([s.lat, s.lng]);
      const marker = L.marker([s.lat, s.lng], { icon: L.divIcon({ className: 'pin ' + s.type, iconSize: [16, 16] }), title: s.name })
        .bindPopup(popupHtml(s)).addTo(layer);
      const li = document.createElement('li');
      li.innerHTML = `<button type="button"><span class="dot ${esc(s.type)}"></span>
        <strong>${esc(s.name)}</strong><span class="kw">${s.powerKw} kW</span>
        <small>${esc(s.corridor || s.city)}, ${esc(s.state)}${s.distanceKm != null ? ' · ' + s.distanceKm + ' km away' : ''}</small></button>`;
      li.firstElementChild.addEventListener('click', () => { map.flyTo([s.lat, s.lng], 11); marker.openPopup(); });
      list.appendChild(li);
    });
    if (points.length) map.fitBounds(points, { padding: [40, 40], maxZoom: userPos ? 11 : 7 });
  }

  function popupHtml(s) {
    const dir = `https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lng}`;
    return `<div class="popup"><h3>${esc(s.name)}</h3>
      <p>${esc(s.corridor || s.city)}, ${esc(s.state)}</p>
      <p><b>${s.powerKw} kW</b> · ${s.chargers} chargers</p>
      <p>${s.connectors.map(esc).join(', ')}</p>
      <p>${s.amenities.map(esc).join(', ')}</p>
      <p><a href="${dir}" target="_blank" rel="noopener">Get directions</a></p></div>`;
  }

  function toggleNearMe() {
    const btn = $('#near');
    if (userPos) {
      userPos = null;
      if (userMarker) { map.removeLayer(userMarker); userMarker = null; }
      btn.setAttribute('aria-pressed', 'false'); btn.textContent = 'Show stations near me';
      $('#radius').hidden = true;
      return loadStations();
    }
    if (!navigator.geolocation) { $('#count').textContent = 'Location is not available in this browser.'; return; }
    navigator.geolocation.getCurrentPosition((pos) => {
      userPos = [pos.coords.latitude, pos.coords.longitude];
      userMarker = L.circleMarker(userPos, { radius: 8, color: '#20262b', fillColor: '#2f80ed', fillOpacity: 1 }).addTo(map);
      btn.setAttribute('aria-pressed', 'true'); btn.textContent = 'Clear location';
      $('#radius').hidden = false;
      loadStations();
    }, () => { $('#count').textContent = 'Could not get your location. Allow location access and try again.'; });
  }

  /* ---------- Vehicles ---------- */
  async function initVehicles(category, listSel, salesSel, noteSel, marketKey) {
    try {
      const [vehicles, market] = await Promise.all([api('vehicles?category=' + category), api('market')]);
      const max = Math.max(...vehicles.map((v) => v.rangeKm));
      $(listSel).innerHTML = vehicles.map((v) => `<li class="veh">
        <div class="veh-name"><strong>${esc(v.brand)} ${esc(v.model)}</strong><span>${esc(v.segment)}</span></div>
        <div class="veh-bar" title="Claimed range, top variant"><i style="width:${Math.round(v.rangeKm / max * 100)}%"></i><b>${v.rangeKm} km</b></div>
        <div class="veh-batt">${esc(v.battery)} kWh battery</div>
        <p>${esc(v.notes)}</p></li>`).join('');
      const m = market[marketKey];
      const top = Math.max(...m.brands.map((b) => b.units));
      $(salesSel).innerHTML = m.brands.map((b) => `<li><span>${esc(b.name)}</span>
        <div><i style="width:${Math.round(b.units / top * 100)}%"></i></div><b>${fmt(b.units)}</b></li>`).join('');
      $(noteSel).textContent = m.note;
    } catch (e) { fail(listSel, e); }
  }

  /* ---------- Policy ---------- */
  function initPolicy() {
    api('policies').then((rows) => {
      $('#policy-list').innerHTML = rows.map((p) => `<article class="policy">
        <div class="badge">${esc(p.badge)}<small>${esc(p.level)} · ${esc(p.status)}</small></div>
        <div><h3>${esc(p.title)}</h3><p>${esc(p.summary)}</p>
        <ul>${p.benefits.map((b) => `<li>${esc(b)}</li>`).join('')}</ul></div></article>`).join('');
    }).catch((e) => fail('#policy-list', e));
  }

  /* ---------- Culture ---------- */
  function initCulture() {
    api('culture').then((c) => {
      $('#pillars').innerHTML = c.pillars.map((p) => `<article><h3>${esc(p.title)}</h3><p>${esc(p.text)}</p></article>`).join('');
      $('#plugs').innerHTML = c.plugs.map((p) => `<dt>${esc(p.name)}</dt><dd>${esc(p.use)}</dd>`).join('');
      $('#trip').innerHTML = c.roadTrip.map((t) => `<li>${esc(t)}</li>`).join('');
      $('#timeline').innerHTML = c.timeline.map((t) => `<li><div class="stone"><span>${esc(t.year)}</span></div><p>${esc(t.event)}</p></li>`).join('');
    }).catch((e) => fail('#pillars', e));
  }

  /* ---------- Routing ---------- */
  const init = {
    map: initMap,
    cars: () => initVehicles('car', '#car-list', '#car-sales', '#car-note', 'cars'),
    bikes: () => initVehicles('2w', '#bike-list', '#bike-sales', '#bike-note', 'twoWheelers'),
    policy: initPolicy,
    culture: initCulture
  };

  function route() {
    const hash = location.hash.slice(1);
    const t = tabs.includes(hash) ? hash : 'map';
    tabs.forEach((id) => { $('#' + id).hidden = id !== t; });
    document.querySelectorAll('#nav a').forEach((a) => {
      if (a.dataset.tab === t) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    if (!loaded[t]) { loaded[t] = true; init[t](); }
    if (t === 'map' && map) setTimeout(() => map.invalidateSize(), 0);
    window.scrollTo(0, 0);
  }

  api('market').then((m) => {
    $('#sources').innerHTML = m.sources.map((s) => `<li><a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.name)}</a></li>`).join('');
  }).catch(() => {});

  window.addEventListener('hashchange', route);
  route();
})();
