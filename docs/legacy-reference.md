# Référence du site historique WebCS

Constat lors de la reprise du 15 septembre 2026 :

- URL publique : `https://tek4all.online/webcounterstrike/`
- la page d'entrée charge une iframe ;
- la cible encore accessible est `indexb.php` ;
- l'interface visible contient notamment : `Plein écran`, `Chat!`, `Joueurs.`, `Scores.`, `Equipe.`, `deconnexion!` et `Connection au serveur!`.

Cette référence sert à conserver la structure fonctionnelle de l'ancienne version pendant la reconstruction.

## Limite du scraping

Un navigateur peut récupérer ce que le serveur lui envoie : HTML rendu, JavaScript, CSS et médias publics. En revanche, le code PHP exécuté côté serveur et la base SQL ne sont pas transmis au navigateur. Si ces sources ne sont plus disponibles chez l'hébergeur, il faut reconstruire cette partie à partir du comportement observable.
