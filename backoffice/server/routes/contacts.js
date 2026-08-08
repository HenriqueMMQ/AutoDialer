const express = require('express');
const multer  = require('multer');
const XLSX    = require('xlsx');
const path    = require('path');
const fs      = require('fs');
const state   = require('../state');

const router  = express.Router();
const upload  = multer({ dest: path.join(__dirname, '../uploads/') });

// POST /api/contacts/upload — receive an Excel file, parse it, and queue a sync to all devices
router.post('/upload', upload.single('file'), (req, res) =>
{
    if (!req.file)
        return res.status(400).json({ error: 'No file uploaded' });

    try
    {
        const workbook = XLSX.readFile(req.file.path);
        const sheet    = workbook.Sheets[workbook.SheetNames[0]];
        const rows     = XLSX.utils.sheet_to_json(sheet, { defval: '' });

        state.sessionContacts = rows.map((row, i) =>
        {
            const name  = row['Name']  || row['Nome']  || '';
            const phone = String(row['Phone'] || row['Telefone'] || row['Number'] || row['Número'] || '').trim();
            return { id: i + 1, name, phone, status: 'pending', calledAt: '', notes: '' };
        }).filter(c => c.phone);

        // Queue the list so devices pick it up on their next poll
        state.pendingContactsSync = { contacts: state.sessionContacts, setAt: new Date().toISOString() };

        fs.unlinkSync(req.file.path);
        res.json({ contacts: state.sessionContacts });
    }
    catch (err)
    {
        res.status(500).json({ error: err.message });
    }
});

// GET /api/contacts — return current session list
router.get('/', (req, res) =>
{
    res.json({ contacts: state.sessionContacts });
});

// PATCH /api/contacts/:id — update a contact's status/notes after a call
router.patch('/:id', (req, res) =>
{
    const contact = state.sessionContacts.find(c => c.id === parseInt(req.params.id));
    if (!contact)
        return res.status(404).json({ error: 'Contact not found' });

    const { status, notes, calledAt } = req.body;
    if (status)   contact.status   = status;
    if (notes !== undefined) contact.notes = notes;
    if (calledAt) contact.calledAt = calledAt;

    res.json({ contact });
});

module.exports = router;
