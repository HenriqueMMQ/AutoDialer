const express = require('express');
const state   = require('../state');

const router = express.Router();

// POST /api/dial — backoffice queues a dial command for the device
router.post('/', (req, res) =>
{
    const { contactId, name, phone } = req.body;
    if (!phone)
        return res.status(400).json({ error: 'phone is required' });

    state.pendingDial = { contactId, name, phone, queuedAt: new Date().toISOString() };
    console.log(`[dial] queued: ${name} — ${phone}`);
    res.json({ queued: state.pendingDial });
});

// GET /api/dial/next — Android polls this; returns pending dial command and/or contacts sync
router.get('/next', (req, res) =>
{
    const response = { pending: null, pendingContacts: null };

    if (state.pendingDial)
    {
        response.pending  = state.pendingDial;
        state.pendingDial = null;
    }

    if (state.pendingContactsSync)
    {
        response.pendingContacts  = state.pendingContactsSync;
        state.pendingContactsSync = null;
    }

    if (state.pendingDncSync)
    {
        response.pendingDncSync = state.pendingDncSync;
        state.pendingDncSync    = null;
    }

    res.json(response);
});

// POST /api/dial/result — Android reports call outcome; updates the session contact list
router.post('/result', (req, res) =>
{
    const { contactId, phone, status, notes, calledAt } = req.body;
    console.log(`[dial] result for contact ${contactId}: ${status}`);

    // Update session contacts so the backoffice sees live results
    let contact = state.sessionContacts.find(c => c.id === contactId);
    if (!contact && phone)
        contact = state.sessionContacts.find(c => c.phone.replace(/\s/g, '') === phone.replace(/\s/g, ''));

    if (contact)
    {
        if (status)   contact.status   = status;
        if (notes !== undefined) contact.notes = notes;
        if (calledAt) contact.calledAt = calledAt;
    }

    res.json({ ok: true });
});

module.exports = router;
