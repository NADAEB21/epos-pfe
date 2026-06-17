import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatiereResponse } from '../../core/api/models';
import { MatiereService } from '../../core/matiere/matiere.service';

@Component({
  selector: 'app-matiere',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './matiere.component.html',
  styleUrl: './matiere.component.scss'
})
export class MatiereComponent implements OnInit {
  matieres: MatiereResponse[] = [];
  filteredMatieres: MatiereResponse[] = [];
  searchTerm: string = '';
  loading = true;

  constructor(private matiereService: MatiereService) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.matiereService.getMatieres().subscribe({
      next: (data) => {
        this.matieres = data;
        this.filteredMatieres = data;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  /**
   * Filtrage par code ou par libellé
   */
  applyFilter(): void {
    const term = this.searchTerm.toLowerCase();
    this.filteredMatieres = this.matieres.filter(m => 
      m.libelle.toLowerCase().includes(term) || 
      m.code.toLowerCase().includes(term)
    );
  }
}