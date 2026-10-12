# MineNorth Casino

Mod Forge 1.20.1 pour MineNorth RP. Dépend de `minenorthapi`, `minenorth_eurobank` et `minenorthentreprises`.
Build : `./gradlew build` (JDK 17) → `build/libs/minenorth-casino-1.0.0.jar`.

## Principe
Le casino est **relié à une entreprise** désignée dans `config/minenorth_casino.json` : les mises des joueurs sont versées
sur le **compte bancaire de l'entreprise**, les gains sont payés **depuis ce compte**. Le casino gagne ou perd de l'argent
réel de l'entreprise. Si la caisse ne peut pas couvrir le gain maximum possible d'une mise, la mise est refusée.

Le joueur paie avec **sa carte bancaire** (en main ou dans l'inventaire, comme au distributeur) et reçoit ses gains sur son compte.
Toutes les parties sont tirées et réglées **côté serveur** (`SecureRandom`) ; le client n'affiche que ce qu'il reçoit.
Les mouvements apparaissent dans l'historique du compte de l'entreprise (« Casino : roulette », « Casino : gain blackjack »…).

## Blocs (onglet créatif « MineNorth Casino », `/give` possible)
- `minenorthcasino:machine_a_sous` : 3 rouleaux, tirage pondéré par symbole.
- `minenorthcasino:table_roulette` : roulette européenne (0-36), paris multiples : plein, douzaines, colonnes, rouge/noir,
  pair/impair, manque/passe. Clic gauche = poser le jeton, clic droit = retirer.
- `minenorthcasino:table_blackjack` : 6 jeux, tirer / rester / doubler, blackjack payé 3 contre 2. Pas de split ni d'assurance.
  Un joueur qui se déconnecte en pleine main : le croupier joue et la main est réglée normalement.

Par défaut seuls les OP posent et cassent ces blocs (`pose_op_seulement`).

## Commandes (OP / console)
- `/casino` : entreprise liée, caisse, retour joueur théorique de la machine, statistiques par jeu (parties, misé, payé, résultat).
- `/casinoreload` : recharge la config.

## Configuration (`config/minenorth_casino.json`)
- `entreprise` : nom exact de l'entreprise (ou `entreprise_id` : numéro, prioritaire s'il est ≥ 0). Vide = casino hors service.
  L'entreprise doit être **active**. Attention : `activites_bloquees` du mod Entreprises ne doit pas contenir « casino ».
- `exiger_entreprise_ouverte` : si `true`, le casino ne marche que quand l'entreprise est ouverte (un membre connecté).
- `pose_op_seulement`, `delai_entre_parties_ms` (anti-spam), `distance_max_blocs`.
- `machine_a_sous` : mises min/max/rapides, `symboles` (`item`, `poids`, `x3` = gain total si 3 identiques,
  `x2` = gain total si 2 identiques). Réglage par défaut : retour joueur ≈ 94 %. `/casino` affiche le retour théorique de la config.
- `roulette` : mises min (par pari) / max (total), `max_paris`, `gains` (gain net : `plein` 35, `douzaine` 2, `colonne` 2, chances simples 1).
- `blackjack` : mises, `gain_blackjack` (1.5), `croupier_tire_soft17`, `nombre_de_jeux`.
