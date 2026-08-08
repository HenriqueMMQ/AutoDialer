const express = require('express');
const multer  = require('multer');
const XLSX    = require('xlsx');
const path    = require('path');
const fs      = require('fs');

const router  = express.Router();
const upload  = multer({ dest: path.join(__dirname, '../uploads/') });

// In-memory session state — replace with a DB when needed
let sessionContacts = [];

// POST /api/contacts/upload — receive an Excel file and parse it
router.post('/upload', upload.single('file'), (req, res) =>
{
    if (!req.file)
        return res.status(400).json({ error: 'No file uploaded' });

    try
    {
        const workbook = XLSX.readFile(req.file.path);
        const sheet    = workbook.Sheets[workbook.SheetNames[0]];
        const rows     = XLSX.utils.sheet_to_json(sheet, { defval: '' });

        sessionContacts = rows.map((row, i) =>
        {
            const name  = row['Name']  || row['Nome']  || '';
            const phone = String(row['Phone'] || row['Telefone'] || row['Number'] || row['Número'] || '').trim();
            return { id: i + 1, name, phone, status: 'pending', calledAt: '', notes: '' };
        }).filter(c => c.phone);

        fs.unlinkSync(req.file.path);
        res.json({ contacts: sessionContacts });
    }
    catch (err)
    {
        res.status(500).json({ error: err.message });
    }
});

// GET /api/contacts — return current session list
router.get('/', (req, res) =>
{
    res.json({ contacts: sessionContacts });
});

// PATCH /api/contacts/:id — update a contact's status/notes after a call
router.patch('/:id', (req, res) =>
{
    const contact = sessionContacts.find(c => c.id === parseInt(req.params.id));
    if (!contact)
        return res.status(404).json({ error: 'Contact not found' });

    const { status, notes, calledAt } = req.body;
    if (status)   contact.status   = status;
    if (notes)    contact.notes    = notes;
    if (calledAt) contact.calledAt = calledAt;

    res.json({ contact });
});

module.exports = router;
