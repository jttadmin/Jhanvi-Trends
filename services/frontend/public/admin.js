// Jhanvi Trends admin panel: dashboard, products (with photos), stock, orders, customers.
// Uses the helpers app.js exposes on window.sv; every call goes to /api/admin/* (admin role only).
(() => {
  const { api, esc, inr, toast } = window.sv;
  const $ = (sel, el = document) => el.querySelector(sel);
  const app = $('#app');
  const TABS = [
    ['dashboard', 'Dashboard'], ['products', 'Products'], ['stock', 'Stock'], ['orders', 'Orders'], ['customers', 'Customers'],
  ];
  const fmtDate = (d) => new Date(d).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  function shell(tab, inner) {
    app.innerHTML = `
      <div class="admin">
        <div class="admin-head"><h1>Store admin</h1><a href="#/">‹ Back to store</a></div>
        <nav class="admin-tabs">${TABS.map(([k, label]) => `<a href="#/admin/${k}" class="${k === tab ? 'active' : ''}">${label}</a>`).join('')}</nav>
        <section id="admin-body">${inner}</section>
      </div>`;
    return $('#admin-body');
  }
  const loadingRow = '<div class="panel"><div class="skeleton" style="height:18px;margin:8px 0"></div><div class="skeleton" style="height:18px;width:70%"></div></div>';
  const failBox = (e) => `<div class="error">${esc(e.message)}</div>`;

  // ---------------- dashboard ----------------
  async function dashboard() {
    const body = shell('dashboard', loadingRow);
    try {
      const s = await api('/admin/summary');
      const card = (label, value, href, warn) =>
        `<a class="stat ${warn ? 'warn' : ''}" href="${href}"><span>${label}</span><b>${value ?? '–'}</b></a>`;
      body.innerHTML = `
        <div class="stats">
          ${card('Products', s.products, '#/admin/products')}
          ${card('Hidden from store', s.hiddenProducts, '#/admin/products')}
          ${card('Low stock (&lt; 10)', s.lowStock, '#/admin/stock', s.lowStock > 0)}
          ${card('Out of stock', s.outOfStock, '#/admin/stock', s.outOfStock > 0)}
          ${card('Orders today', s.ordersToday, '#/admin/orders')}
          ${card('Revenue today', `₹${inr(s.revenueToday)}`, '#/admin/orders')}
          ${card('Customers', s.customers, '#/admin/customers')}
        </div>
        <div class="panel"><h3 style="margin-top:0">Quick actions</h3>
          <button class="btn" id="qa-add">+ Add product</button>
          <a class="btn ghost" href="#/admin/stock" style="display:inline-block">Update stock</a>
        </div>`;
      $('#qa-add').onclick = () => productForm(null, () => { location.hash = '#/admin/products'; });
    } catch (e) {
      body.innerHTML = failBox(e);
    }
  }

  // ---------------- products ----------------
  async function products(params) {
    const q = params.get('q') || '';
    const body = shell('products', loadingRow);
    try {
      const { items, total } = await api(`/admin/products${q ? `?q=${encodeURIComponent(q)}` : ''}`);
      body.innerHTML = `
        <div class="admin-bar">
          <form id="p-search" class="admin-search"><input name="q" placeholder="Search products" value="${esc(q)}" /><button class="btn ghost">Search</button></form>
          <button class="btn" id="p-add">+ Add product</button>
        </div>
        <p class="muted">${total} products${q ? ` matching “${esc(q)}”` : ''}</p>
        <div class="table-wrap"><table class="admin-table">
          <thead><tr><th></th><th>Product</th><th>Category</th><th class="num">Price</th><th class="num">Stock</th><th>Status</th><th></th></tr></thead>
          <tbody>${items.map((p) => `
            <tr data-id="${p.id}">
              <td><img class="thumb-sm" src="${esc(p.imageUrl)}" alt="" /></td>
              <td><b>${esc(p.name)}</b><br /><span class="muted">${esc(p.brand)} · #${p.id}</span></td>
              <td>${esc(p.category)}</td>
              <td class="num">₹${inr(p.price)}<br /><span class="muted strike">₹${inr(p.mrp)}</span></td>
              <td class="num ${p.stock === 0 ? 'bad' : p.stock < 10 ? 'warn' : ''}">${p.stock ?? '–'}</td>
              <td><span class="badge ${p.active ? 'on' : 'off'}">${p.active ? 'Visible' : 'Hidden'}</span></td>
              <td class="row-actions"><button class="linkish" data-edit>Edit</button><button class="linkish" data-toggle>${p.active ? 'Hide' : 'Show'}</button><button class="linkish danger" data-del>Delete</button></td>
            </tr>`).join('') || '<tr><td colspan="7" class="muted">No products yet.</td></tr>'}
          </tbody></table></div>`;
      $('#p-search').onsubmit = (e) => {
        e.preventDefault();
        const v = new FormData(e.target).get('q').trim();
        location.hash = `#/admin/products${v ? `?q=${encodeURIComponent(v)}` : ''}`;
      };
      $('#p-add').onclick = () => productForm(null, () => products(params));
      body.querySelectorAll('tr[data-id]').forEach((tr) => {
        const p = items.find((x) => String(x.id) === tr.dataset.id);
        tr.querySelector('[data-edit]').onclick = () => productForm(p, () => products(params));
        tr.querySelector('[data-toggle]').onclick = async () => {
          try {
            await api(`/admin/products/${p.id}`, { method: 'PUT', body: { ...editable(p), active: !p.active } });
            toast(p.active ? 'Hidden from the store' : 'Visible in the store');
            products(params);
          } catch (e) { toast(e.message, true); }
        };
        tr.querySelector('[data-del]').onclick = async () => {
          if (!confirm(`Delete “${p.name}” permanently? Its photo and stock record are removed too. Past orders keep their copy of the name and price.`)) return;
          try {
            await api(`/admin/products/${p.id}`, { method: 'DELETE' });
            toast('Product deleted');
            products(params);
          } catch (e) { toast(e.message, true); }
        };
      });
    } catch (e) {
      body.innerHTML = failBox(e);
    }
  }

  const editable = (p) => ({
    name: p.name, category: p.category, brand: p.brand, price: p.price, mrp: p.mrp,
    description: p.description, features: p.features, active: p.active,
  });

  async function productForm(p, onSaved) {
    const isNew = !p;
    let categories = [];
    try { categories = await api('/admin/categories'); } catch { /* free text still works */ }
    const modal = document.createElement('div');
    modal.className = 'modal-backdrop';
    modal.innerHTML = `
      <form class="modal" id="pf">
        <div class="modal-head"><h2>${isNew ? 'Add product' : `Edit product #${p.id}`}</h2><button type="button" class="linkish" data-close aria-label="Close">✕</button></div>
        <div class="modal-body">
          <div class="photo-row">
            <img id="pf-img" class="thumb-lg" src="${p ? esc(p.imageUrl) : ''}" alt="" ${p ? '' : 'hidden'} />
            <div>
              <label>Photo <span class="muted">(JPEG, PNG, WebP or GIF, up to 3 MB)</span></label>
              <input type="file" id="pf-file" accept="image/jpeg,image/png,image/webp,image/gif" />
              ${p && p.hasPhoto ? '<button type="button" class="linkish danger" id="pf-rmimg">Remove photo</button>' : ''}
            </div>
          </div>
          <label>Product name</label><input name="name" required minlength="2" maxlength="200" value="${esc(p?.name || '')}" />
          <div class="row2">
            <div><label>Brand</label><input name="brand" required maxlength="60" value="${esc(p?.brand || '')}" /></div>
            <div><label>Category</label><input name="category" required minlength="2" maxlength="60" list="pf-cats" value="${esc(p?.category || '')}" />
              <datalist id="pf-cats">${categories.map((c) => `<option value="${esc(c.name)}">`).join('')}</datalist></div>
          </div>
          <div class="row2">
            <div><label>Selling price (₹)</label><input name="price" type="number" min="1" step="0.01" required value="${p?.price ?? ''}" /></div>
            <div><label>M.R.P. (₹)</label><input name="mrp" type="number" min="1" step="0.01" required value="${p?.mrp ?? ''}" /></div>
          </div>
          ${isNew ? '<label>Opening stock</label><input name="initialStock" type="number" min="0" step="1" value="0" />' : ''}
          <label>Description</label><textarea name="description" rows="3" maxlength="3000">${esc(p?.description || '')}</textarea>
          <label>Key features <span class="muted">(one per line)</span></label><textarea name="features" rows="4">${esc((p?.features || []).join('\n'))}</textarea>
          <label class="check"><input type="checkbox" name="active" ${!p || p.active ? 'checked' : ''} /> Visible in the store</label>
          <div id="pf-err"></div>
        </div>
        <div class="modal-foot"><button type="button" class="btn ghost" data-close>Cancel</button><button class="btn" id="pf-save">${isNew ? 'Add product' : 'Save changes'}</button></div>
      </form>`;
    document.body.appendChild(modal);
    const close = () => modal.remove();
    modal.querySelectorAll('[data-close]').forEach((b) => (b.onclick = close));
    modal.addEventListener('click', (e) => { if (e.target === modal) close(); });

    let file = null;
    $('#pf-file', modal).onchange = (e) => {
      file = e.target.files[0] || null;
      const err = $('#pf-err', modal);
      err.innerHTML = '';
      if (file && file.size > 3 * 1024 * 1024) {
        err.innerHTML = '<div class="error">Photo must be 3 MB or smaller.</div>';
        file = null;
        e.target.value = '';
        return;
      }
      if (file) {
        const im = $('#pf-img', modal);
        im.src = URL.createObjectURL(file);
        im.hidden = false;
      }
    };
    const rm = $('#pf-rmimg', modal);
    if (rm) {
      rm.onclick = async () => {
        try {
          const upd = await api(`/admin/products/${p.id}/image`, { method: 'DELETE' });
          $('#pf-img', modal).src = upd.imageUrl;
          rm.remove();
          toast('Photo removed');
        } catch (e) { toast(e.message, true); }
      };
    }

    $('#pf', modal).onsubmit = async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      const body = {
        name: f.get('name'), brand: f.get('brand'), category: f.get('category'),
        price: Number(f.get('price')), mrp: Number(f.get('mrp')),
        description: f.get('description'),
        features: String(f.get('features') || '').split('\n').map((x) => x.trim()).filter(Boolean),
        active: f.get('active') === 'on',
      };
      if (isNew) body.initialStock = Number(f.get('initialStock') || 0);
      const save = $('#pf-save', modal);
      save.disabled = true;
      save.textContent = 'Saving…';
      try {
        const saved = isNew
          ? await api('/admin/products', { method: 'POST', body })
          : await api(`/admin/products/${p.id}`, { method: 'PUT', body });
        if (file) {
          save.textContent = 'Uploading photo…';
          const res = await fetch(`/api/admin/products/${saved.id}/image`, {
            method: 'PUT', body: file,
            headers: { 'content-type': file.type, authorization: `Bearer ${window.sv.state.token}` },
          });
          if (!res.ok) {
            const d = await res.json().catch(() => ({}));
            throw new Error(`Product saved, but the photo failed: ${d.error || res.status}`);
          }
        }
        toast(isNew ? 'Product added' : 'Product saved');
        close();
        onSaved();
      } catch (err) {
        $('#pf-err', modal).innerHTML = `<div class="error">${esc(err.message)}</div>`;
        save.disabled = false;
        save.textContent = isNew ? 'Add product' : 'Save changes';
      }
    };
    $('input[name=name]', modal).focus();
  }

  // ---------------- stock ----------------
  async function stock(params) {
    const filter = params.get('filter') || 'all';
    const body = shell('stock', loadingRow);
    try {
      const { items } = await api('/admin/products');
      const rows = items
        .filter((p) => filter === 'all' || (filter === 'low' ? (p.stock ?? 0) < 10 : (p.stock ?? 0) === 0))
        .sort((a, b) => (a.stock ?? 0) - (b.stock ?? 0));
      body.innerHTML = `
        <div class="admin-bar">
          <div class="seg">${[['all', 'All'], ['low', 'Low (< 10)'], ['out', 'Out of stock']].map(([k, l]) =>
            `<a href="#/admin/stock?filter=${k}" class="${k === filter ? 'active' : ''}">${l}</a>`).join('')}</div>
          <span class="muted">Set a new quantity, or add / remove units, then press Enter or Save.</span>
        </div>
        <div class="table-wrap"><table class="admin-table">
          <thead><tr><th></th><th>Product</th><th class="num">In stock</th><th>Set quantity</th><th>Add / remove</th></tr></thead>
          <tbody>${rows.map((p) => `
            <tr data-id="${p.id}">
              <td><img class="thumb-sm" src="${esc(p.imageUrl)}" alt="" /></td>
              <td><b>${esc(p.name)}</b><br /><span class="muted">#${p.id}${p.active ? '' : ' · hidden'}</span></td>
              <td class="num ${p.stock === 0 ? 'bad' : p.stock < 10 ? 'warn' : ''}" data-cur>${p.stock ?? '–'}</td>
              <td><form data-set class="inline"><input type="number" min="0" step="1" value="${p.stock ?? 0}" aria-label="New quantity" /><button class="btn ghost">Save</button></form></td>
              <td class="adj"><button class="btn ghost" data-adj="-10">−10</button><button class="btn ghost" data-adj="-1">−1</button><button class="btn ghost" data-adj="1">+1</button><button class="btn ghost" data-adj="10">+10</button></td>
            </tr>`).join('') || '<tr><td colspan="5" class="muted">Nothing here. 🎉</td></tr>'}
          </tbody></table></div>`;
      body.querySelectorAll('tr[data-id]').forEach((tr) => {
        const id = tr.dataset.id;
        const show = (n) => {
          const cur = $('[data-cur]', tr);
          cur.textContent = n;
          cur.className = `num ${n === 0 ? 'bad' : n < 10 ? 'warn' : ''}`;
          $('[data-set] input', tr).value = n;
        };
        $('[data-set]', tr).onsubmit = async (e) => {
          e.preventDefault();
          try {
            const r = await api(`/admin/stock/${id}`, { method: 'PUT', body: { available: Number($('input', e.target).value) } });
            show(r.available);
            toast('Stock updated');
          } catch (err) { toast(err.message, true); }
        };
        tr.querySelectorAll('[data-adj]').forEach((b) => (b.onclick = async () => {
          try {
            const r = await api(`/admin/stock/${id}/adjust`, { method: 'POST', body: { delta: Number(b.dataset.adj) } });
            show(r.available);
          } catch (err) { toast(err.message, true); }
        }));
      });
    } catch (e) {
      body.innerHTML = failBox(e);
    }
  }

  // ---------------- orders ----------------
  async function orders(params) {
    const status = params.get('status') || '';
    const body = shell('orders', loadingRow);
    try {
      const all = await api('/admin/orders');
      const list = status ? all.filter((o) => o.status === status) : all;
      const addr = (a) => (a ? [a.fullName, a.line1, a.city, a.pincode].filter(Boolean).map(esc).join(', ') : '');
      body.innerHTML = `
        <div class="admin-bar">
          <div class="seg">${[['', 'All'], ['CONFIRMED', 'Confirmed'], ['PAYMENT_FAILED', 'Payment failed']].map(([k, l]) =>
            `<a href="#/admin/orders${k ? `?status=${k}` : ''}" class="${k === status ? 'active' : ''}">${l}</a>`).join('')}</div>
          <span class="muted">Latest ${all.length} orders</span>
        </div>
        <div class="table-wrap"><table class="admin-table">
          <thead><tr><th>Order</th><th>Placed</th><th>Customer</th><th>Items</th><th class="num">Total</th><th>Status</th></tr></thead>
          <tbody>${list.map((o) => `
            <tr>
              <td><b>${esc(o.id.slice(0, 8).toUpperCase())}</b></td>
              <td>${fmtDate(o.createdAt)}</td>
              <td>${addr(o.shippingAddress)}<br /><span class="muted">user #${esc(o.userId)}</span></td>
              <td>${o.items.map((i) => `${i.quantity} × ${esc(i.name)}`).join('<br />')}</td>
              <td class="num">₹${inr(o.totalAmount)}</td>
              <td><span class="badge ${o.status === 'CONFIRMED' ? 'on' : 'off'}">${esc(o.status.replace('_', ' ').toLowerCase())}</span></td>
            </tr>`).join('') || '<tr><td colspan="6" class="muted">No orders yet.</td></tr>'}
          </tbody></table></div>`;
    } catch (e) {
      body.innerHTML = failBox(e);
    }
  }

  // ---------------- customers ----------------
  async function customers(params) {
    const q = params.get('q') || '';
    const body = shell('customers', loadingRow);
    try {
      const { items, total } = await api(`/admin/customers${q ? `?q=${encodeURIComponent(q)}` : ''}`);
      body.innerHTML = `
        <div class="admin-bar">
          <form id="c-search" class="admin-search"><input name="q" placeholder="Search name or email" value="${esc(q)}" /><button class="btn ghost">Search</button></form>
          <span class="muted">${total} customers</span>
        </div>
        <div class="table-wrap"><table class="admin-table">
          <thead><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Joined</th></tr></thead>
          <tbody>${items.map((u) => `
            <tr><td>${u.id}</td><td>${esc(u.name)}</td><td>${esc(u.email)}</td>
              <td><span class="badge ${u.role === 'admin' ? 'on' : ''}">${esc(u.role)}</span></td><td>${fmtDate(u.createdAt)}</td></tr>`).join('')
            || '<tr><td colspan="5" class="muted">No customers found.</td></tr>'}
          </tbody></table></div>`;
      $('#c-search').onsubmit = (e) => {
        e.preventDefault();
        const v = new FormData(e.target).get('q').trim();
        location.hash = `#/admin/customers${v ? `?q=${encodeURIComponent(v)}` : ''}`;
      };
    } catch (e) {
      body.innerHTML = failBox(e);
    }
  }

  const PAGES = { dashboard, products, stock, orders, customers };
  window.svAdmin = { page: (name, params) => (PAGES[name] || dashboard)(params || new URLSearchParams()) };
})();
