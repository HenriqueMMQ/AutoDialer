const express = require('express');
const router  = express.Router();

// In-memory store: deviceId → { contacts, syncedAt }
const deviceContacts = {};

// POST /api/device/contacts — Android app pushes its loaded contact list
router.post('/contacts', (req, res) =>
{
    const { deviceId, contacts } = req.body;
    if (!deviceId)
        return res.status(400).json({ error: 'deviceId is required' });
    if (!Array.isArray(contacts))
        return res.status(400).json({ error: 'contacts must be an array' });

    deviceContacts[deviceId] = { contacts, syncedAt: new Date().toISOString() };
    console.log(`[device] ${deviceId} synced ${contacts.length} contacts`);
    res.json({ ok: true, count: contacts.length });
});

// GET /api/device/contacts — backoffice fetches all synced device contact lists
router.get('/contacts', (req, res) =>
{
    const devices = Object.entries(deviceContacts).map(([deviceId, data]) => ({
        deviceId,
        contacts: data.contacts,
        syncedAt: data.syncedAt,
    }));
    res.json({ devices });
});

module.exports = router;
