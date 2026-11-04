# MineNorth Casino

Mod Forge 1.20.1 pour MineNorth RP. Dépend de `minenorthapi`, `minenorth_eurobank` et `minenorthentreprises`.
Utilise aussi, s'ils sont présents (par réflexion, sans dépendance de compilation) : les espèces d'EuroBank, les gangs du mod Portes
(`GangApi`) et les alertes du mod Police (`RadarHack.raise`).
Build : `./gradlew build` (JDK 17) → `build/libs/minenorth-casino-1.0.0.jar`.

## Principe
Le casino est **relié à une entreprise** désignée dans `config/minenorth_casino.json` : les mises des joueurs sont versées
sur le **compte bancaire de l'entreprise**, les gains sont payés **depuis ce compte**. Le casino gagne ou perd de l'argent
réel de l'entreprise. Si la caisse ne peut pas couvrir le gain maximum possible d'une mise, la mise est refusée.

Les jeux se jouent avec des **jetons** (objets de 1, 5, 25, 100 et 500 €). On les **achète à la caisse** avec sa carte bancaire (le montant va sur le compte de l'entreprise) et on les **rachète** à la caisse : tous les jetons repris, leur valeur est versée sur le compte du joueur (la caisse doit avoir assez d'argent). Les mises sont prises dans l'inventaire (monnaie rendue en jetons) et les gains rendus en jetons ; seuls les centimes (ex. blackjack 3 contre 2) passent par le compte. Mises en euros entiers. Un joueur déconnecté en pleine main de blackjack est payé directement sur son compte.
Toutes les parties sont tirées et réglées **côté serveur** (`SecureRandom`) ; le client n'affiche que ce qu'il reçoit.
Les mouvements apparaissent dans l'historique du compte de l'entreprise (« Casino : roulette », « Casino : gain blackjack »…).

## Blocs (onglet créatif « MineNorth Casino », `/give` possible)
- `machine_a_sous` (blanche et or), `machine_a_sous_bleue`, `machine_a_sous_rouge` : machine haute de 2 blocs avec couronne et levier, 3 rouleaux, tirage pondéré par symbole.
- `caisse_casino` : la caisse (achat / rachat des jetons). Jetons : `jeton_1`, `jeton_5`, `jeton_25`, `jeton_100`, `jeton_500`.
- `table_roulette` : grande table de 3 x 2 blocs (roue à gauche, tapis à droite), se pose et se casse d'un bloc. Roulette européenne (0-36), paris multiples : plein, douzaines, colonnes, rouge/noir,
  pair/impair, manque/passe. Clic gauche = poser le jeton, clic droit = retirer.
- `table_poker` : table de **Texas Hold'em** de 3 x 2 blocs (voir plus bas).
- `arene_coqs` : arène clandestine de **combats** (coqs de la maison ou animaux des joueurs) de **5 x 4 blocs** (voir plus bas).
- `table_blackjack` : grande table de 3 x 2 blocs (demi-lune, plateau de jetons et sabot), se pose et se casse d'un bloc. 6 jeux, tirer / rester / doubler, blackjack payé 3 contre 2. Pas de split ni d'assurance.
  Un joueur qui se déconnecte en pleine main : le croupier joue et la main est réglée normalement.

Par défaut seuls les OP posent et cassent ces blocs (`pose_op_seulement`).

## Poker (Texas Hold'em No Limit, 6 places)
- Clic droit sur la table : l'écran montre le tapis ovale et les 6 places. Choisir une **cave** (de `cave_min_blinds` à
  `cave_max_blinds` grosses blinds), cliquer une place libre : les jetons sont pris dans l'inventaire et posés devant soi.
- Une main démarre dès que 2 joueurs sont assis (3 s plus tard). Bouton du donneur qui tourne, petite et grosse blind (en
  tête-à-tête, le bouton paie la petite blind et parle en premier avant le flop), deux cartes chacun, puis flop, tournant, rivière.
- Actions : **se coucher, check, suivre, miser / relancer** (montant réglable : − / +, molette, ½ pot, pot), **tapis**. Relance
  minimale = dernière relance ; un tapis inférieur ne rouvre pas les enchères.
- **Showdown et pots annexes** : chaque pot va au(x) meilleur(s) joueur(s) qui y ont contribué, partage à égalité (l'euro indivisible
  au premier gagnant après le bouton). L'excédent non suivi revient à son propriétaire.
- **Commission du croupier** (`commission_pourcent`, plafonnée à `commission_max_euros`) prise sur le pot, seulement si la main voit
  le flop. Les jetons gardés restent au casino (l'entreprise n'aura pas à les racheter) : `/casino` la compte comme résultat du poker.
- **Temps de parole** (`temps_par_action_secondes`) : écoulé, check si possible sinon couché ; deux fois de suite, le joueur est levé.
- **Se lever** rend le tapis en jetons. Déconnexion : couché, puis le tapis est viré sur son compte par l'entreprise. Table cassée ou
  serveur arrêté en pleine main : la main est annulée, chacun récupère sa mise et son tapis. (Un plantage brutal du serveur perd la
  main en cours : les tapis ne sont gardés qu'en mémoire.)
- **Anti-triche** : le serveur mélange (`SecureRandom`), distribue, vérifie chaque action (tour, montant, tapis) et n'envoie à chaque
  joueur que SES cartes ; celles des autres ne partent qu'au showdown. Un joueur ne peut s'asseoir qu'à une seule table.

## Combats de coqs clandestins
- **Hors casino** : pas d'entreprise, paris en **espèces** (billets EuroBank sur soi ou dans le portefeuille), monnaie rendue.
- Deux coqs tirés au hasard (nom, plumage, force, agilité, endurance). Le premier pari lance le compte à rebours
  (`duree_paris_secondes`), puis le combat : deux vrais poulets apparaissent dans l'arène, bondissent l'un sur l'autre (plumes,
  coups critiques, esquives) et l'écran rejoue chaque échange avec les points de vie. Le perdant s'effondre.
- **Cotes au pari mutuel** : les mises perdantes paient les gagnants au prorata de leur mise ; l'organisateur garde
  `commission_pourcent` des mises perdantes. Cote affichée = gain pour 1 € (théorique tant qu'aucun pari, calculée sur ~600 combats
  simulés). Personne sur le vainqueur : tout le monde est remboursé. On ne parie que sur un coq par combat. **Aucun argent créé.**
- **Gangs** : `gang_organisateur` = le gang (mod Portes) qui tient l'arène. Ses membres voient la caisse (les commissions) et la
  récupèrent en espèces. Sans gang configuré, les commissions partent au trésor public. Le tag de gang apparaît devant les parieurs.
- **L'arène bouge** comme les boutiques illégales : poser plusieurs `arene_coqs` (OP), une seule est ouverte à la fois ; toutes les
  `deplacement_minutes`, elle change d'emplacement (pas pendant un combat, pas si un joueur est à moins de `rayon_presence_blocs`).
  Une arène fermée ressemble à un tas de caisses bâché, sans rien à y faire.
- **Police** : au début de chaque combat, `chance_alerte_police` qu'un témoin prévienne la police (alerte avec position approximative,
  repère de carte, onglet ALERTES de la tablette). Un policier **en service** (`police_en_service`) qui ouvre l'arène a le bouton
  **DÉMANTELER** : mises en cours et caisse saisies (trésor public, « police:saisie_combats_de_coqs »), liste des parieurs, arène
  fermée partout pendant `fermeture_apres_descente_minutes`. Un policier ne peut pas parier.
- Gains d'un joueur parti avant la fin : remis en espèces à sa prochaine connexion. Arrêt du serveur ou arène démontée pendant les
  paris : mises rendues.

## Arène : animaux des joueurs, entraînement, menu de l'organisateur (04/11)
- **Arène agrandie : 5 x 4 blocs** (ring de sable, palissade, gradins en bottes de paille, caisse du bookmaker, guirlande d'ampoules ;
  version fermée = gros tas de caisses bâché). Les combattants se font face à 3 blocs l'un de l'autre. Propriété de bloc à part
  (`ArenaBlock.PART_A`, 0 à 19) : les autres tables gardent 0 à 5. **Les arènes déjà posées (3 x 2) sont à casser et reposer.**
- **Combattre avec son animal** : onglet « Mon animal » de l'écran de l'arène. Le maître se tient près de l'arène avec son animal
  apprivoisé (chien, chat, animal Polly Pets… à moins de `distance_animal_blocs`, pas un chien de police) et l'**inscrit** à la place d'un
  coq de la maison (une place par côté, un animal par maître, avant le premier pari). Dans l'arène se bat un **double** de l'animal
  (même apparence, sans IA, invulnérable, retiré à la fin) : le vrai animal ne risque rien. Une inscription vaut pour un combat ; après, l'animal
  se repose `repos_animal_minutes`. Retrait possible tant qu'aucun pari n'est engagé.
- **Entraînement** (même onglet) : +1 en force, agilité ou endurance (plafond `animal_qualite_max`, départ `animal_qualites_depart`), payé en
  **espèces** (`prix_entrainement_euros` x niveau actuel), une séance toutes les `entrainement_minutes` par animal. Pas pendant une inscription.
  Fiche par animal (qualités, victoires, défaites) dans la sauvegarde de l'arène (`minenorth_casino_coqs`).
- **Menu de l'organisateur** (onglet « Organisation » : membres du `gang_organisateur` et staff OP) :
  - **Arène ouverte / fermée** : fermée, l'emplacement actif reste bâché (personne d'autre ne peut l'ouvrir) ; pas pendant des paris ou un combat ;
  - **Lancement automatique / à la main** : à la main, les paris restent ouverts jusqu'au bouton **« Lancer le combat maintenant »**
    (le bouton marche aussi en automatique pour couper le compte à rebours) ;
  - **Commission** de 0 à `commission_max_pourcent` (pas de 1 %, entre deux combats) ;
  - **Bénéficiaire de la commission** : caisse du gang (trésor public sans gang), **maître de l'animal vainqueur**, moitié / moitié, ou
    **un joueur désigné** (pseudo exact, connecté). Si le vainqueur est un coq de la maison, la part « maître » va à la caisse.
- Réseau : **protocole 5** (mod à installer serveur ET clients).

## Jackpot progressif et gros gains
- Une part de chaque mise des machines à sous (`part_des_mises_pourcent`) alimente un **jackpot commun** à toutes les machines,
  affiché sur l'écran. Trois symboles **les plus rares** (le dernier de la liste) remportent le jackpot en plus du gain normal ; il
  repart de `montant_depart_euros`. Il est payé par l'entreprise comme les autres gains (la caisse doit couvrir gain max + jackpot).
- Les gains d'au moins `annonce_gros_gain_euros` (machines, roulette, blackjack) sont annoncés aux joueurs à moins de
  `rayon_annonce_blocs`, avec un son de fête.

## Modèles et écrans
- Modèles générés par `python tools/gen_casino.py` (textures, modèles, états de blocs, tables de loot, aperçus dans `docs/`) :
  distributeur de jetons (caisse) avec écran incliné, enseigne néon « CASINO », bac à jetons et lecteur de carte ; table de poker
  ovale (boudin en cuir, piste en bois, rack du croupier, sabot, cartes et piles de jetons) ; arène ouverte (palissade, sable,
  paille, lanterne, guirlande) et fermée (caisses bâchées) ; jetons en relief ; blackjack et roulette avec ceinture, pieds tournés,
  boudin en cuir, rack, sabot et cuvette de roue en relief (les tapis d'origine sont gardés, relevés dans `tools/plateaux_tables.json`).
  Aperçus : `docs/apercu-casino-3d.png` et un PNG par modèle. Rendu hors jeu : `tools/rendu_modele.py`.
- Écrans : charte « Vice City de nuit » (panneaux très arrondis, liseré rose → orange, boutons en pilule), cartes arrondies,
  bandeau du jackpot qui pulse.

## Idées pour plus tard
- **Roulette multijoueur** : un tirage commun toutes les X secondes, tout le monde mise sur la même table (et voit les jetons des autres).
- **Salon VIP** : tables de poker à grosses blinds réservées aux grades VIP, commission réduite pour les VIP.
- **Tournois de poker** (sit & go) avec droit d'entrée et répartition des prix ; classement des meilleurs joueurs.
- **Croupier PNJ** animé derrière les tables, et jetons posés en vrai sur le tapis (rendu de bloc).
- **Combines de gang** : un gang peut « acheter » le combat (coq dopé, cotes faussées) au risque d'une enquête de police.
- **Carte de fidélité casino** : points cumulés, cadeaux, accès VIP temporaire.

## Commandes (OP / console)
- `/casino` : entreprise liée, caisse, retour joueur théorique de la machine, jackpot, état des combats de coqs (emplacements, arène
  ouverte, combats, saisies, caisse), statistiques par jeu (parties, misé, payé, résultat ; poker : commission encaissée).
- `/casinoreload` : recharge la config.

## Configuration (`config/minenorth_casino.json`)
- `entreprise` : nom exact de l'entreprise (ou `entreprise_id` : numéro, prioritaire s'il est ≥ 0). Vide = casino hors service.
  L'entreprise doit être **active**. Attention : `activites_bloquees` du mod Entreprises ne doit pas contenir « casino ».
- `exiger_entreprise_ouverte` : si `true`, le casino ne marche que quand l'entreprise est ouverte (un membre connecté).
- `pose_op_seulement`, `delai_entre_parties_ms` (anti-spam), `distance_max_blocs`.
- `caisse` : `achat_min_euros`, `achat_max_euros`, `achats_rapides_euros`.
- `machine_a_sous` : mises min/max/rapides, `symboles` (`item`, `poids`, `x3` = gain total si 3 identiques,
  `x2` = gain total si 2 identiques). Réglage par défaut : retour joueur ≈ 94 %. `/casino` affiche le retour théorique de la config.
- `roulette` : mises min (par pari) / max (total), `max_paris`, `gains` (gain net : `plein` 35, `douzaine` 2, `colonne` 2, chances simples 1).
- `blackjack` : mises, `gain_blackjack` (1.5), `croupier_tire_soft17`, `nombre_de_jeux`.
- `poker` : `petite_blind_euros`, `grosse_blind_euros`, `cave_min_blinds` / `cave_max_blinds`, `commission_pourcent` (5),
  `commission_max_euros` (20), `temps_par_action_secondes` (30), `pause_entre_mains_secondes`.
- `jackpot` : `actif`, `part_des_mises_pourcent` (1), `montant_depart_euros` (500), `annonce_gros_gain_euros`, `rayon_annonce_blocs`.
- `combats_de_coqs` : mises min / max (espèces, par combat), `mises_rapides_euros`, `duree_paris_secondes`, `pause_secondes`,
  `commission_pourcent` (10), `gang_organisateur`, `deplacement_minutes`, `rayon_presence_blocs`, `chance_alerte_police`,
  `police_en_service`, `fermeture_apres_descente_minutes` ; animaux : `animal_qualites_depart` (3), `animal_qualite_max` (9),
  `prix_entrainement_euros` (25), `entrainement_minutes` (30), `repos_animal_minutes` (10), `distance_animal_blocs` (10),
  `commission_max_pourcent` (30).
Toutes ces sections s'ajoutent seules au fichier au démarrage.


