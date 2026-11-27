# 📚 StudyPlanner - Organisateur de révisions

Application web d'organisation de révisions et de suivi de tâches avec authentification utilisateur, stockage sécurisé côté serveur en Java et interface responsive en HTML/CSS/JavaScript.

## ✨ Fonctionnalités

- **Authentification sécurisée** : inscription, connexion, déconnexion et sessions avec expiration automatique (24 h).
- **Sécurité** : mots de passe hachés avec **PBKDF2WithHmacSHA256** (65 536 itérations + sel aléatoire unique par utilisateur), comparaison en temps constant, protection contre les *race conditions* (`putIfAbsent`), headers HTTP de sécurité et gestion globale des erreurs.
- **Persistance serveur** : les tâches de chaque utilisateur sont sauvegardées côté serveur via l'API `/api/tasks` dans le dossier `data/`.
- **Gestion complète des tâches** : ajout, édition en modale, suppression avec confirmation, filtrage par statut/priorité, recherche instantanée, tri (date, priorité, récent) et barre de progression.
- **Mode sombre sans flash** : thème persisté dans le navigateur et appliqué avant le premier rendu.

## 🗂️ Structure du projet

```
study-planner/
├── css/
│   └── style.css
├── js/
│   └── app.js
├── AuthServer.java
├── index.html
└── README.md
```

## 🚀 Installation et exécution

1. **Compiler le serveur Java** (JDK 17+ recommandé) :
   ```bash
   javac AuthServer.java
   ```
2. **Démarrer le serveur** :
   ```bash
   java AuthServer
   ```
3. **Ouvrir l'application** dans ton navigateur à l'adresse :
   `http://localhost:8081`

Le dossier `data/` ainsi que le fichier `users.db` sont créés automatiquement au démarrage.

## 🛠️ Technologies utilisées

- **Backend** : Java
- **Frontend** : HTML5, CSS3
- **Icônes & typographie** : Font Awesome, Google Fonts
