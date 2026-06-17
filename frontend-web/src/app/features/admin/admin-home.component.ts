import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdminHomeService, ExamStats } from '../../core/admin-home/admin-home.service';
import { ExamenResponse, MatiereResponse } from '../../core/api/models';

@Component({
  selector: 'app-admin-home',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './admin-home.component.html',
  styleUrl: './admin-home.component.scss'
})
export class AdminHomeComponent implements OnInit {
  recentExams: ExamenResponse[] = [];
  matieres: MatiereResponse[] = [];
  
  // Données pour les cartes
  totalUsers = 0;
  totalStations = 0;
  activeExamsCount = 0; // Somme de CONFIGURE + EN_COURS

  loading = true;
  stats: ExamStats = { BROUILLON: 0, CONFIGURE: 0, EN_COURS: 0, TERMINE: 0, ARCHIVE: 0 };

  constructor(private ds: AdminHomeService) {}

  ngOnInit(): void {
    this.loadMatieres(); 
    this.loadDashboard();
  }

  loadMatieres(): void {
    this.ds.getMatieres().subscribe(res => this.matieres = res.data || []);
  }

  loadDashboard(): void {
    this.loading = true;

    // 1. Récupérer le nombre total d'utilisateurs
    this.ds.getUsersCount().subscribe(c => this.totalUsers = c);

    // 2. Récupérer le nombre total de stations
    this.ds.getStationsCount().subscribe(c => this.totalStations = c);

    // 3. Récupérer les examens et calculer les statistiques
    this.ds.getExamens(0, 50).subscribe({
      next: (res) => {
        const data = res.data;
        this.recentExams = data.content || [];
        
        // Calculer les stats par statut (BROUILLON, CONFIGURE, etc.)
        this.stats = this.ds.calculateStats(this.recentExams);
        
        // Calculer spécifiquement les "Examens Actifs" pour la carte n°2
        this.activeExamsCount = this.stats.CONFIGURE + this.stats.EN_COURS;
        
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  getMatiereNom(id: number): string {
    const m = this.matieres.find(mat => mat.id === id);
    return m ? m.libelle : 'Chargement...';
  }

  onShow(id: number): void { console.log("Afficher examen:", id); }
}