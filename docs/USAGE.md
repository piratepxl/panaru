# Guida all'uso

## 1. Preparare Gokapi

1. Accedi all'interfaccia web del tuo Gokapi.
2. Menu **API** → crea una nuova chiave. Permessi:
   - **Upload** — obbligatorio;
   - **View** — per vedere download rimasti/scadenze e l'elenco dei file sul server;
   - **Delete** — per eliminare un link dalla cronologia di Panaru;
   - **Download** — per scaricare i file dal server dentro Panaru.
3. Copia la chiave.

## 2. Aggiungere il server

Indirizzo: lo stesso che usi nel browser (es. `https://files.example.com`, anche con sottopercorso
`https://example.com/gokapi`). Se incolli un link come `…/admin` o `…/d?id=…`, Panaru lo ripulisce.
**Verifica connessione** controlla chiave e permesso Upload e mostra versione e dimensione massima.

Puoi aggiungere più server (personale, lavoro, remoto) e sceglierne uno predefinito.
Ogni upload resta associato al server su cui è stato caricato.

## 3. Condividere

### Android
- **Da qualsiasi app:** *Condividi → Panaru*. Con l'impostazione predefinita l'upload parte subito
  sul server predefinito con scadenza e download predefiniti; il link viene copiato e compare la
  notifica **Link pronto** con *Copia* e *Condividi*.
  Disattivando *Carica subito dalla condivisione* puoi scegliere server, scadenza, download e
  password prima di caricare.
- **Dall'app:** *Scegli file* (anche selezione multipla), oppure la scorciatoia **Carica** tenendo
  premuta l'icona di Panaru.
- In cronologia: tocca una voce per copiare il link, tieni premuto per selezionarne più di una e
  condividerle insieme.

### macOS
- Trascina file o cartelle nella finestra, sull'icona del Dock o nel riquadro della barra dei menu.
- Finder: clic destro → **Servizi → Carica con Panaru** (attivabile da Impostazioni di Sistema →
  Tastiera → Scorciatoie → Servizi), oppure **Apri con → Panaru**.
- `⌘O` apre il selettore con le opzioni (server, scadenza, download, password).
- Seleziona più voci in cronologia e `⌘C` copia tutti i link.

## 4. Opzioni di condivisione

| Opzione | Valori | Note |
|---------|--------|------|
| Scadenza | 1, 3, 7, 14, 30 giorni, mai | |
| Download consentiti | 1, 3, 5, 10, illimitati | |
| Password | facoltativa | solo caratteri ASCII (limite dell'API Gokapi); non viene salvata |

## 5. Domande frequenti

**L'upload si è interrotto, riprende da dove era?** No: Gokapi non offre upload riprendibili.
*Riprova* ricomincia da capo.

**Il mio Gokapi usa la cifratura end-to-end.** Gli upload via API (quindi da Panaru, come da
qualunque client API) sono salvati non cifrati: Panaru lo segnala nella verifica del server.

**Perché HTTP non è accettato?** Su Internet la chiave API viaggerebbe in chiaro. HTTP è consentito
solo per indirizzi della rete locale.
