const express = require('express');

const router = express.Router();

// Pending dial command the Android app will pick up on its next poll
let pendingDial = null;

// POST /api/dial — backoffice tells the server to dial a contact
router.post('/', (req, res) =>
{
    const { contactId, name, phone } = req.body;
    if (!phone)
        return res.status(400).json({ error: 'phone is required' });

    pendingDial = { contactId, name, phone, queuedAt: new Date().toISOString() };
    console.log(`[dial] queued: ${name} — ${phone}`);
    res.json({ queued: pendingDial });
});

// GET /api/dial/next — Android app polls this; returns and clears the pending command
router.get('/next', (req, res) =>
{
    if (!pendingDial)
        return res.json({ pending: null });

    const command = pendingDial;
    pendingDial   = null;
    res.json({ pending: command });
});

// POST /api/dial/result — Android app reports the outcome after the call ends
router.post('/result', (req, res) =>
{
    const { contactId, status, notes, calledAt } = req.body;
    console.log(`[dial] result for contact ${contactId}: ${status}`);
    // TODO: persist result and push update to connected backoffice clients (SSE / WebSocket)
    res.json({ ok: true });
});

module.exports = router;
