import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ExamenAdminService, ExamStats } from '../../core/examen-admin/examen-admin.service';
import { ExamenResponse, MatiereResponse, StatutExamen } from '../../core/api/models';

@Component({
  selector: 'app-examen-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './examen-admin.component.html',
  styleUrl: './examen-admin.component.scss'
})
export class ExamenAdminComponent implements OnInit {
  examForm: FormGroup;
  recentExams: ExamenResponse[] = [];
  matieres: MatiereResponse[] = []; // Liste pour le select
  totalExamens = 0;
  loading = true;
  isEditing = false;
  currentExamenId: number | null = null;
  stats = { BROUILLON: 0, CONFIGURE: 0, EN_COURS: 0, TERMINE: 0, ARCHIVE: 0 };

  constructor(private ds: ExamenAdminService, private fb: FormBuilder) {
    this.examForm = this.fb.group({
      nom: ['', [Validators.required, Validators.minLength(3)]],
      matiereId: [null, Validators.required],
      dateExamen: ['', Validators.required],
      statut: ['BROUILLON', Validators.required],
      description: ['']
    });
  }

  ngOnInit(): void {
    this.loadMatieres(); // <--- Appel indispensable
    this.loadData();
  }

  loadMatieres(): void {
    this.ds.getMatieres().subscribe({
      next: (res) => {
        this.matieres = res.data || [];
        console.log('Matières chargées dans Examen-Admin:', this.matieres);
      },
      error: (err) => console.error('Erreur chargement matières:', err)
    });
  }

  getMatiereNom(id: number): string {
    const m = this.matieres.find(mat => mat.id === id);
    return m ? m.libelle : 'Chargement...';
  }

  loadData(): void {
    this.loading = true;
    this.ds.getExamens(0, 50).subscribe({
      next: (res) => {
        this.recentExams = res.data.content || [];
        this.totalExamens = res.data.totalElements || this.recentExams.length;
        this.stats = this.ds.calculateStats(this.recentExams);
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  onSubmit(): void {
    if (this.examForm.invalid) return;
    const action = (this.isEditing && this.currentExamenId) 
      ? this.ds.modifierExamen(this.currentExamenId, this.examForm.value)
      : this.ds.creerExamen(this.examForm.value);

    action.subscribe({
      next: () => {
        this.resetForm();
        this.loadData();
      },
      error: (err) => alert('Erreur: ' + (err.error?.message || 'Inconnue'))
    });
  }

  onEdit(examen: ExamenResponse): void {
    this.isEditing = true;
    this.currentExamenId = examen.id;
    this.examForm.patchValue(examen);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  onDelete(id: number): void {
    if (confirm('Supprimer cet examen ?')) {
      this.ds.supprimerExamen(id).subscribe(() => this.loadData());
    }
  }

  resetForm(): void {
    this.isEditing = false;
    this.currentExamenId = null;
    this.examForm.reset({ statut: 'BROUILLON', matiereId: null });
  }
}