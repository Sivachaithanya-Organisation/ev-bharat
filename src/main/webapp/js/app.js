(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const api = (p) => fetch('api/' + p).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmt = (n) => Number(n).toLocaleString('en-IN');
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const fail = (sel, e) => { $(sel).innerHTML = `<p class="err">Could not load data (${esc(e.message)}). Is the API running?</p>`; };

  const tabs = ['map', 'cars', 'bikes', 'policy', 'culture', 'rent'];
  const loaded = {};
  let map, layer, userMarker, userPos = null;
  let rentLayer = null, me = null, pickMap = null, pickMarker = null;
  let baseStats = null, rentalCount = 0;

  function renderStats() {
    if (!baseStats) return;
    const s = baseStats;
    $('#stats').innerHTML = [
      [s.stations + rentalCount, 'stations'], [s.highway, 'on highways'], [s.states, 'states and UTs'],
      [s.fastDc, 'fast (50 kW+)'], ...(rentalCount ? [[rentalCount, 'for rent']] : [])
    ].map(([n, l]) => `<div><b>${fmt(n)}</b>${l}</div>`).join('');
  }
  let allStations = [], visibleStations = [], routePlaces = [];
  let routeLine = null, routeHitLayer = null, activeRoute = null;

  /* ---------- Finder ---------- */
  function initMap() {
    map = L.map('leaflet').setView([22.5, 79], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);
    map.setMaxBounds([[2, 60], [40, 102]]);
    map.options.minZoom = 4;
    layer = L.markerClusterGroup({
      maxClusterRadius: 70, disableClusteringAtZoom: 12, showCoverageOnHover: false,
      iconCreateFunction: (c) => {
        const kids = c.getAllChildMarkers();
        const hw = kids.filter((m) => m.options.stype === 'highway').length;
        const cls = hw * 2 >= kids.length ? 'highway' : 'city';
        return L.divIcon({ html: `<span>${kids.length}</span>`, className: 'cluster ' + cls, iconSize: [40, 40] });
      }
    }).addTo(map);
    rentLayer = L.layerGroup().addTo(map);
    loadRentalPoints();

    api('stats').then((s) => {
      baseStats = s;
      renderStats();
    }).catch((e) => fail('#stats', e));

    Promise.all([api('stations/meta'), api('stations')]).then(([m, stations]) => {
      const uniq = (arr) => {
        const seen = new Map();
        arr.forEach((v) => { const t = String(v ?? '').trim(); const k = t.toLowerCase(); if (t && k !== 'unknown' && !seen.has(k)) seen.set(k, t); });
        return [...seen.values()].sort((a, b) => a.localeCompare(b));
      };
      $('select[name=state]').insertAdjacentHTML('beforeend', uniq(m.states).map((s) => `<option>${esc(s)}</option>`).join(''));
      $('select[name=connector]').insertAdjacentHTML('beforeend', uniq(m.connectors).map((c) => `<option>${esc(c)}</option>`).join(''));
      allStations = stations;
      buildRoutePlaces(stations);
    }).catch(() => {}).finally(loadStations);

    const form = $('#filters');
    form.addEventListener('input', debounce(loadStations, 200));
    $('.route-box').addEventListener('input', (e) => e.stopPropagation());
    form.addEventListener('submit', (e) => e.preventDefault());
    $('#near').addEventListener('click', toggleNearMe);
    $('#routeGo').addEventListener('click', setRoute);
    $('#routeClear').addEventListener('click', clearRoute);
  }

  function loadStations() {
    const p = new URLSearchParams(new FormData($('#filters')));
    [...p.keys()].forEach((k) => { if (!p.get(k)) p.delete(k); });
    if (userPos) { p.set('lat', userPos[0]); p.set('lng', userPos[1]); } else { p.delete('radiusKm'); }
    api('stations?' + p).then((rows) => {
      visibleStations = rows;
      if (activeRoute) {
        rows = rows.filter((s) => distancePointToRouteKm([s.lat, s.lng], activeRoute.coords) <= ROUTE_KM);
        updateRouteInfo(rows.length);
      }
      renderStations(rows);
      if (activeRoute) drawRouteLine();
    }).catch((e) => fail('#list', e));
  }

  const ROUTE_KM = 12;

  function renderStations(rows) {
    layer.clearLayers();
    const list = $('#list');
    list.innerHTML = '';
    $('#count').textContent = rows.length === 1 ? '1 station' : rows.length + ' stations';
    const points = [];
    rows.forEach((s) => {
      points.push([s.lat, s.lng]);
      const marker = L.marker([s.lat, s.lng], { icon: L.divIcon({ className: 'pin ' + s.type, iconSize: [16, 16] }), title: s.name, stype: s.type })
        .bindPopup(popupHtml(s)).addTo(layer);
      const li = document.createElement('li');
      li.innerHTML = `<button type="button"><span class="dot ${esc(s.type)}"></span>
        <strong>${esc(s.name)}</strong><span class="kw">${s.powerKw} kW</span>
        <small>${esc(s.corridor || s.city)}, ${esc(s.state)}${s.distanceKm != null ? ' · ' + s.distanceKm + ' km away' : ''}</small></button>`;
      li.firstElementChild.addEventListener('click', () => {
        map.flyTo([s.lat, s.lng], 12);
        map.once('moveend', () => layer.zoomToShowLayer(marker, () => marker.openPopup()));
      });
      list.appendChild(li);
    });
    if (points.length) map.fitBounds(points, { padding: [40, 40], maxZoom: userPos ? 11 : 7 });
  }

  function buildRoutePlaces(stations) {
    const seen = new Set();
    const places = [];
    const add = (label, lat, lng) => {
      const k = label.trim().toLowerCase();
      if (!k || seen.has(k)) return;
      seen.add(k);
      places.push({ label: label.trim(), lat, lng });
    };
    const bad = (v) => !v || !String(v).trim() || String(v).trim().toLowerCase() === 'unknown';
    const cityMap = new Map();
    stations.forEach((s) => {
      if (bad(s.city)) return;
      const label = bad(s.state) ? String(s.city).trim() : `${String(s.city).trim()}, ${String(s.state).trim()}`;
      const key = label.toLowerCase();
      if (!cityMap.has(key)) cityMap.set(key, { label, latSum: 0, lngSum: 0, count: 0 });
      const c = cityMap.get(key);
      c.latSum += s.lat;
      c.lngSum += s.lng;
      c.count += 1;
    });
    for (const c of cityMap.values()) add(c.label, c.latSum / c.count, c.lngSum / c.count);
    routePlaces= places.sort((a, b) => a.label.localeCompare(b.label));
    $('#routePoints').innerHTML = routePlaces.map((p) => `<option value="${esc(p.label)}"></option>`).join('');
  }

  function resolveRoutePoint(value) {
    const raw = String(value ?? '').trim();
    if (!raw) return null;
    const low = raw.toLowerCase();
    const exact = routePlaces.find((p) => p.label.toLowerCase() === low);
    if (exact) return exact;
    const starts = routePlaces.find((p) => p.label.toLowerCase().startsWith(low));
    if (starts) return starts;
    const m = raw.match(/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/);
    if (m) {
      return { label: raw, lat: Number(m[1]), lng: Number(m[2]) };
    }
    return null;
  }

  async function setRoute() {
    const from = resolveRoutePoint($('#fromPoint').value);
    const to = resolveRoutePoint($('#toPoint').value);
    if (!from || !to) {
      $('#routeInfo').textContent = 'Pick both route points from the list (or enter "lat,lng").';
      return;
    }
    if (from.lat === to.lat && from.lng === to.lng) {
      $('#routeInfo').textContent = 'From and destination are the same.';
      return;
    }
    $('#routeInfo').textContent = 'Finding route…';
    const coords = await fetchRoutePath(from, to);
    activeRoute = { from, to, coords };
    loadStations();
  }

  function clearRoute() {
    activeRoute = null;
    $('#fromPoint').value = '';
    $('#toPoint').value = '';
    $('#routeInfo').textContent = '';
    if (routeLine) { map.removeLayer(routeLine); routeLine = null; }
    if (routeHitLayer) { map.removeLayer(routeHitLayer); routeHitLayer = null; }
    loadStations();
  }

  async function fetchRoutePath(from, to) {
    const url = `https://router.project-osrm.org/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson`;
    try {
      const r = await fetch(url);
      const body = await r.json();
      const c = body?.routes?.[0]?.geometry?.coordinates;
      if (Array.isArray(c) && c.length > 1) {
        return c.map((p) => [p[1], p[0]]);
      }
    } catch (_) {}
    return [[from.lat, from.lng], [to.lat, to.lng]];
  }

  function drawRouteLine() {
    if (!activeRoute || !map) return;
    if (routeLine) map.removeLayer(routeLine);
    routeLine = L.polyline(activeRoute.coords, { color: '#2f80ed', weight: 4, opacity: 0.9 }).addTo(map);
    const bounds = routeLine.getBounds();
    layer.eachLayer((m) => bounds.extend(m.getLatLng()));
    map.fitBounds(bounds, { padding: [30, 30], maxZoom: 9 });
  }

  function updateRouteInfo(n) {
    const routeKm = Math.round(polylineKm(activeRoute.coords));
    $('#routeInfo').textContent =
      `${activeRoute.from.label} to ${activeRoute.to.label}: ${n} stations within ${ROUTE_KM} km of route (${routeKm} km).`;
    $('#count').textContent = n === 1 ? '1 station on route' : n + ' stations on route';
  }

  function polylineKm(coords) {
    let km = 0;
    for (let i = 1; i < coords.length; i += 1) {
      km += haversineKm(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1]);
    }
    return km;
  }

  function distancePointToRouteKm(point, coords) {
    if (coords.length < 2) return haversineKm(point[0], point[1], coords[0][0], coords[0][1]);
    let best = Number.POSITIVE_INFINITY;
    for (let i = 1; i < coords.length; i += 1) {
      const d = distancePointToSegmentKm(point, coords[i - 1], coords[i]);
      if (d < best) best = d;
    }
    return best;
  }

  function distancePointToSegmentKm(p, a, b) {
    const refLat = ((p[0] + a[0] + b[0]) / 3) * Math.PI / 180;
    const pp = projectToKm(p, refLat);
    const aa = projectToKm(a, refLat);
    const bb = projectToKm(b, refLat);
    const vx = bb.x - aa.x;
    const vy = bb.y - aa.y;
    const wx = pp.x - aa.x;
    const wy = pp.y - aa.y;
    const c1 = vx * wx + vy * wy;
    const c2 = vx * vx + vy * vy;
    const t = c2 === 0 ? 0 : Math.max(0, Math.min(1, c1 / c2));
    const px = aa.x + t * vx;
    const py = aa.y + t * vy;
    return Math.hypot(pp.x - px, pp.y - py);
  }

  function projectToKm(latLng, refLatRad) {
    const rad = Math.PI / 180;
    const R = 6371.0088;
    return {
      x: R * latLng[1] * rad * Math.cos(refLatRad),
      y: R * latLng[0] * rad
    };
  }

  function haversineKm(lat1, lon1, lat2, lon2) {
    const R = 6371.0088;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2
      + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
      * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(a));
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
    const setPos = (lat, lng) => {
      userPos = [lat, lng];
      userMarker = L.circleMarker(userPos, { radius: 8, color: '#20262b', fillColor: '#2f80ed', fillOpacity: 1 }).addTo(map);
      btn.setAttribute('aria-pressed', 'true'); btn.textContent = 'Clear location';
      $('#radius').hidden = false;
      loadStations();
    };
    const manual = (why) => {
      const name = window.prompt(why + '\nEnter your city (e.g. Pune, Maharashtra) to find stations near it:');
      const p = name && resolveRoutePoint(name);
      if (p) setPos(p.lat, p.lng); else if (name) $('#count').textContent = 'City not found. Pick one from the From/Destination suggestions.';
    };
    if (!navigator.geolocation || !window.isSecureContext) {
      return manual('Automatic location needs HTTPS or localhost.');
    }
    navigator.geolocation.getCurrentPosition((pos) => setPos(pos.coords.latitude, pos.coords.longitude),
      (err) => manual(err.code === 1 ? 'Location is blocked for this site.' : 'Could not get your location.'),
      { enableHighAccuracy: false, timeout: 15000, maximumAge: 60000 });
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

  /* ---------- Accounts & rental chargers ---------- */
  const post = (path, body, method = 'POST') => fetch('api/' + path, {
    method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined
  }).then(async (r) => {
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data.error || 'Request failed (' + r.status + ')');
    return data;
  });

  function setUser(u) {
    me = u;
    $('#loginOpen').hidden = !!u;
    $('#userMenu').hidden = !u;
    $('#userName').textContent = u ? u.name : '';
    if ($('#rentGuest')) {
      $('#rentGuest').hidden = !!u;
      $('#rentMember').hidden = !u;
    }
    if (u) { loadMyPoints(); setTimeout(() => pickMap && pickMap.invalidateSize(), 0); } else { $('#myPoints').innerHTML = ''; }
    loadRentalPoints();
  }

  function initAccount() {
    const dlg = $('#authDialog');
    let mode = 'login';
    const setMode = (m) => {
      mode = m;
      $('#authTitle').textContent = m === 'login' ? 'Log in' : 'Create account';
      $('#authSubmit').textContent = m === 'login' ? 'Log in' : 'Sign up';
      $('#nameRow').hidden = m === 'login';
      $('#authSwitch').textContent = m === 'login' ? 'New here? Create an account' : 'Already registered? Log in';
      $('#authForm').password.autocomplete = m === 'login' ? 'current-password' : 'new-password';
      $('#authMsg').textContent = '';
    };
    const open = () => { setMode('login'); $('#authForm').reset(); dlg.showModal(); };
    $('#loginOpen').addEventListener('click', open);
    $('#rentLogin').addEventListener('click', open);
    $('#authCancel').addEventListener('click', () => dlg.close());
    $('#authSwitch').addEventListener('click', (e) => { e.preventDefault(); setMode(mode === 'login' ? 'register' : 'login'); });
    $('#authForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = e.target;
      const body = { email: f.email.value, password: f.password.value };
      if (mode === 'register') body.name = f.name.value;
      try {
        setUser(await post('auth/' + mode, body));
        dlg.close();
      } catch (err) { $('#authMsg').textContent = err.message; }
    });
    $('#logoutBtn').addEventListener('click', async () => {
      try { await post('auth/logout'); } catch (_) {}
      setUser(null);
    });

    initPointForm();
    fetch('api/auth/me').then((r) => (r.ok ? r.json() : null)).then(setUser).catch(() => setUser(null));
  }

  function initPointForm() {
    pickMap = L.map('pickMap').setView([22.5, 79], 4);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18, attribution: '&copy; OpenStreetMap contributors' }).addTo(pickMap);
    pickMap.on('click', (e) => {
      const f = $('#pointForm');
      f.lat.value = e.latlng.lat.toFixed(6);
      f.lng.value = e.latlng.lng.toFixed(6);
      if (pickMarker) pickMarker.setLatLng(e.latlng); else pickMarker = L.marker(e.latlng).addTo(pickMap);
    });
    $('#pointForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = e.target;
      const msg = $('#pointMsg');
      if (!f.lat.value) { msg.textContent = 'Click the map to place your charger.'; return; }
      try {
        await post('points', {
          title: f.title.value, city: f.city.value, state: f.state.value, connector: f.connector.value,
          powerKw: Number(f.powerKw.value), pricePerKwh: Number(f.pricePerKwh.value), phone: f.phone.value,
          notes: f.notes.value, lat: Number(f.lat.value), lng: Number(f.lng.value)
        });
        msg.textContent = 'Charger added. It is now visible on the map.';
        f.reset();
        if (pickMarker) { pickMap.removeLayer(pickMarker); pickMarker = null; }
        loadMyPoints();
        loadRentalPoints();
      } catch (err) { msg.textContent = err.message; }
    });
  }

  function loadMyPoints() {
    fetch('api/points/mine').then((r) => (r.ok ? r.json() : [])).then((rows) => {
      $('#myPoints').innerHTML = rows.length ? rows.map((p) => `<li><div><strong>${esc(p.title)}</strong>
        <small>${esc(p.city)}, ${esc(p.state)} · ${p.powerKw} kW · ${esc(p.connector)} · ₹${p.pricePerKwh}/kWh</small></div>
        <button type="button" class="acct-btn ghost" data-del="${p.id}">Remove</button></li>`).join('')
        : '<li class="small">You have not added a charger yet.</li>';
      document.querySelectorAll('[data-del]').forEach((b) => b.addEventListener('click', async () => {
        if (!window.confirm('Remove this charger?')) return;
        try { await post('points/' + b.dataset.del, null, 'DELETE'); loadMyPoints(); loadRentalPoints(); } catch (err) { window.alert(err.message); }
      }));
    }).catch(() => {});
  }

  function loadRentalPoints() {
    if (!map || !rentLayer) return;
    fetch('api/points').then((r) => (r.ok ? r.json() : [])).then((rows) => {
      rentLayer.clearLayers();
      rentalCount = rows.length;
      renderStats();
      rows.forEach((p) => {
        const contact = p.phone ? `<p>Contact: <a href="tel:${esc(p.phone)}">${esc(p.phone)}</a></p>` : '<p><small>Log in to see the contact number.</small></p>';
        L.marker([p.lat, p.lng], { icon: L.divIcon({ className: 'pin rental', iconSize: [16, 16] }), title: p.title })
          .bindPopup(`<div class="popup"><h3>${esc(p.title)}</h3><p><span class="tag">For rent</span> ${esc(p.city)}, ${esc(p.state)}</p>
            <p><b>${p.powerKw} kW</b> · ${esc(p.connector)} · ₹${p.pricePerKwh}/kWh</p>${p.notes ? `<p>${esc(p.notes)}</p>` : ''}${contact}</div>`)
          .addTo(rentLayer);
      });
    }).catch(() => {});
  }

  /* ---------- Routing ---------- */
  const init = {
    map: initMap,
    rent: () => {},
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
    if (t === 'rent' && pickMap) setTimeout(() => pickMap.invalidateSize(), 0);
    window.scrollTo(0, 0);
  }

  api('market').then((m) => {
    $('#sources').innerHTML = m.sources.map((s) => `<li><a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.name)}</a></li>`).join('');
  }).catch(() => {});

  window.addEventListener('hashchange', route);
  initAccount();
  route();
})();
