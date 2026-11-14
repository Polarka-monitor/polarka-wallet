# Polarka Wallet: krátký návod

Polarka Wallet je zkušební verze. Zkontroloval ji autor s pomocí AI a nezávislý recenzent, kterým je model
AI. Nezávislý lidský odborník ji zatím nekontroloval.
Mějte v ní jen malé částky. Větší úspory patří na papír (studená adresa).

## Před instalací

- Telefon s Androidem 12 nebo novějším a se zámkem obrazovky. S otiskem prstu je to pohodlné.
- Balíček (`.apk`) ze stránky projektu a dva otisky zveřejněné vedle něj.
- Balíček před instalací ověřte. Na počítači:

      sha256sum polarka-wallet-X.Y.Z.apk
      apksigner verify --print-certs polarka-wallet-X.Y.Z.apk

  První číslo se musí rovnat zveřejněnému SHA-256 daného vydání. SHA-256 certifikátu musí být u každého vydání
  `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b`. Pokud se cokoli liší, soubor neinstalujte.
- Android se zeptá, zda smí aplikace, kterou jste soubor otevřeli, instalovat. Povolte to pro tento jeden případ.

## První spuštění: server, nebo vlastní uzel

Peněženka nemá vlastní server. Na stav vašich adres se ptá jinde a je na vás, kde.

- **Veřejný server** je nejrychlejší cesta a nabízí se jako první. Předvyplněný je server projektu Northbound
  (`https://api.northboundwallet.org`); neprovozují ho autoři této aplikace. Server vidí všechny adresy vaší
  peněženky pohromadě a vaši internetovou adresu. Mohl by vám ukázat nepravdivý zůstatek, zatajit příchozí
  platbu nebo platbu zdržet. Mince vám vzít nemůže: slova ani klíče telefon neopouštějí. Když server neběží,
  peněženka nic neukáže; mince zůstávají vaše a připojíte se jinam. Místo předvyplněné adresy lze napsat
  adresu jiného serveru se stejným rozhraním.
- **Vlastní uzel** nechá vaše adresy jen vám. Viz poslední kapitolu.

Co je přes server jinak než s vlastním uzlem:

- Platba, která čeká na blok, se v zůstatku projeví hned: mince, ze které se platí, se nepočítá a zbytek
  z ní je uveden zvlášť jako částka na cestě.
- U peněženky s více než sto adresami (zhruba po padesáti platbách) může v historii u některých plateb chybět
  poplatek a výjimečně celý řádek. Stránka Historie to řekne. Zůstatek a mince jsou úplné vždy.
- Stránka těžby neukazuje výkon sítě a odměnu za blok pozná jen do chvíle, než je utracena.

Jednou spusťte vlastní test (první obrazovka nebo Více). Ukáže, že kód pro klíče na vašem telefonu funguje.

## Založení peněženky

1. Přehled: zvolte 24 nebo 12 slov a "Vytvořit novou peněženku". 24 slov nese 256 bitů náhodnosti, 12 slov
   128 bitů; dvanáct slov se opisuje snáz a otevře je i peněženka Northbound pro počítač. Počet slov nelze
   u hotové peněženky změnit. Telefon slova ukáže.
2. Opište je rukou na papír, v daném pořadí. Nefoťte je, nikam je nepište, neukládejte je do žádné aplikace.
   Kdo má slova, má mince. Bez nich znamená ztracený nebo rozbitý telefon ztracené mince.
3. Aplikace se zeptá na tři ze slov a potom slova zapečetí vaším otiskem nebo zámkem obrazovky.
4. Než na peněženku pošlete mince, zkontrolujte celý papír: Více, "Kontrola celého papíru". Napište všechna slova
   z papíru. Aplikace řekne, zda jsou to slova této peněženky. Tři slova, na která se aplikace zeptala,
   neukážou, že jsou správně ta ostatní; tato kontrola ano. Aplikace slova už nikdy neukáže, takže
   chybu nalezenou později lze napravit jen přesunem mincí do nové peněženky.
5. Papír uložte tam, kam se nedostane oheň, voda ani zvědaví lidé.

Pokud v telefonu zrušíte zámek obrazovky, Android zničí klíč, který slova chrání. Aplikace vám to oznámí;
peněženku pak vrátí jen papír.

## Příjem

Záložka Přijmout ukáže adresu a její QR kód. Dejte ji plátci. Platba se objeví po svém prvním bloku, obvykle
během několika minut; dřív ji peněženka nevidí. Po použití adresy nabídne peněženka novou; staré adresy fungují dál.

## Odeslání

1. Odeslat: vložte adresu nebo načtěte její QR kód, napište částku (nebo zaškrtněte "Odeslat vše"), zvolte poplatek.
2. "Zkontrolovat platbu" ukáže adresu, částku, poplatek a to, co se vám vrátí. Adresu porovnejte s jejím zdrojem
   znak po znaku.
3. "Podepsat otiskem a odeslat". Odeslanou platbu nelze vzít zpět.

Dokud nepřijde blok, je platba v Přehledu uvedena jako čekající a její mince se znovu nenabízejí. Pokud uzel
nebo server nepotvrdí, že platbu převzal, aplikace řekne, že výsledek není znám. Neplaťte pak znovu: za minutu obnovte
přehled a aplikace ukáže, zda platba čeká na blok, nebo neproběhla.

Platba, která neproběhla, zůstane v Přehledu jako "Neproběhla, ještě může dorazit": uzel, kterého se aplikace
ptá, ji nedrží, ale jiný uzel ji držet může. Zaplatit znovu můžete hned. Aplikace postaví novou platbu na
minci té staré, takže dorazit může vždy jen jedna z nich, a záznam odstraní, jakmile o věci rozhodne blok.
"Zapomenout tuto platbu" je pro vzácný případ, kdy záznam zůstává a vy máte jistotu, že stará platba už
dorazit nemůže.

## Obnova peněženky

Více, "Obnova peněženky": napište 12 nebo 24 slov oddělených mezerami. Mince se najdou v řetězci, ať byla
peněženka používaná jakkoli dlouho.

Pokud slova nejsou platná, aplikace řekne, co je špatně, a napsaný text nechá, abyste ho mohli opravit:
slovo, které není v seznamu, pojmenuje i s pořadím ("5 (slovox)" je páté slovo), špatný počet slov spočítá
a u slov, která v seznamu jsou, ale nepatří k sobě, není aspoň jedno to správné, nebo není správné pořadí.
Pokud jste napsali přesně to, co je na papíře, je papír opsaný chybně. Napsaný text zmizí, když stránku
opustíte, a když se do aplikace vrátíte po více než minutě.

Vzácně dá chybné slovo stále platnou sadu: u 24 slov zhruba jedno chybné slovo z 256, u 12 slov jedno z 16.
Aplikace pak obnoví jinou, prázdnou peněženku. Pokud se peněženka, na které mají být mince, obnoví prázdná,
podívejte se nejdřív znovu na papír.

Papír, který nikdo nevyzkoušel, je naděje, ne záloha. Zkontrolujte ho, dokud je peněženka v telefonu
(Více, "Kontrola celého papíru"), nebo z něj peněženku jednou obnovte s malou částkou.

Sama od sebe aplikace prohlíží prvních 200 adres peněženky, u veřejného uzlu a u serveru 40. Pokud uzel ukazuje, že jich
je použito víc, zeptá se Přehled, zda má aplikace hledat dál. Řekněte ano, pokud jste peněženku opravdu tolik
používali. Pokud ne, uzel ukazuje něco, co není pravda: řekněte ne a použijte jiný uzel. Pokud po obnově
chybí mince, o kterých víte, je na stránce Více volba "Prohlédnout dvakrát tolik".

## Vybrání papírové adresy

Více, "Vybrání papírové adresy": napište slova papírové adresy. Aplikace ukáže, co na ní je. Přesuňte vše,
do této peněženky nebo zčásti na novou papírovou adresu: papír, jehož slova byla napsána do telefonu, už není
studený. Odměny za bloky mladší než 100 bloků zatím přesunout nejdou; aplikace řekne, kolik zůstává a na jak
dlouho, a papír je pak třeba uschovat a adresu vybrat ještě jednou. Nikdy do telefonu nepište slova papírové
adresy, na které je víc, než byste v telefonu nosili.

## Zámek a odebrání

Aplikace se po minutě na pozadí zamkne a chce otisk znovu (Více, "Při otevření aplikace chtít otisk prstu").
Zámek v horní liště ji zamkne hned; telefon se pak na otisk zeptá až po klepnutí na Odemknout.
Odebrání peněženky z telefonu zničí uložená slova; mince zůstávají v řetězci a dostanete se k nim slovy
z papíru. Odinstalování aplikace nebo vymazání jejích dat udělá totéž.

## Vlastní uzel

Na počítači s TrueNorth Core (v30.5.4 nebo novější) přidejte do `truenorth.conf`:

    server=1
    blockfilterindex=1
    rpcauth=phone:<vytvořeno skriptem rpcauth.py, stejně jako u Bitcoin Core>
    rpcwhitelist=phone:getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee,testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps
    rpcwhitelistdefault=0

Tento uživatel smí číst řetězec a předávat hotové transakce, nic jiného. Telefon potřebuje https s certifikátem,
kterému důvěřuje: nejjednodušší je Tailscale Serve před portem RPC (uzel je pak dosažitelný jen z vašich
zařízení); poslouží i webový server s certifikátem. Potom vytvořte soubor s nastavením a pošlete ho do telefonu:

    {"format":"polarka-setup/2","node":{"url":"https://vas-uzel.example","user":"phone","password":"..."},"chain":"main","addresses":[]}

V `addresses` může být až dvacet adres, které chcete sledovat bez jejich klíčů, například adresa, na kterou
těžíte. Soubor načtěte na první obrazovce ("Vybrat soubor s nastavením"). Heslo se neukazuje, ukládá se
zašifrované a aplikace nabídne soubor potom smazat.
