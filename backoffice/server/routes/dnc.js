const express = require('express');
const fs      = require('fs');
const path    = require('path');
const state   = require('../state');

const router   = express.Router();
const DNC_FILE = path.join(__dirname, '../dnc.json');

function norm(phone) { return String(phone).replace(/\D/g, ''); }

function loadFromDisk()
{
    try { return new Set(JSON.parse(fs.readFileSync(DNC_FILE, 'utf8'))); }
    catch { return new Set(); }
}

function saveToDisk()
{
    fs.writeFileSync(DNC_FILE, JSON.stringify([...state.dncSet]), 'utf8');
}

// Initialise from disk on first require
state.dncSet = loadFromDisk();

// GET /api/dnc
router.get('/', (req, res) =>
{
    res.json({ phones: [...state.dncSet] });
});

// POST /api/dnc  { phones: string[] }
router.post('/', (req, res) =>
{
    const phones = Array.isArray(req.body.phones) ? req.body.phones : [];
    const added  = phones.map(norm).filter(Boolean);
    added.forEach(p => state.dncSet.add(p));
    saveToDisk();
    state.pendingDncSync = { phones: [...state.dncSet], setAt: new Date().toISOString() };
    // Flag any already-loaded session contacts that are now on DNC
    state.sessionContacts.forEach(c => { if (state.dncSet.has(norm(c.phone))) c.status = 'dnc'; });
    console.log(`[dnc] added ${added.length}, total ${state.dncSet.size}`);
    res.json({ ok: true, count: state.dncSet.size });
});

// DELETE /api/dnc/:phone
router.delete('/:phone', (req, res) =>
{
    const p = norm(req.params.phone);
    state.dncSet.delete(p);
    saveToDisk();
    state.pendingDncSync = { phones: [...state.dncSet], setAt: new Date().toISOString() };
    console.log(`[dnc] removed ${p}, total ${state.dncSet.size}`);
    res.json({ ok: true, count: state.dncSet.size });
});

module.exports = router;
