import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule, FormsModule } from '@angular/forms';
import { StationService, StationUI } from '../../core/station/station.service';

@Component({
  selector: 'app-station',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './station.component.html',
  styleUrl: './station.component.scss'
})
export class StationComponent implements OnInit {
  stationForm: FormGroup;
  stations: StationUI[] = [];
  grilleTemplates: string[] = []; // Liste pour le menu déroulant
  isEditing = false;
  currentStationId: number | null = null;
  totalStations = 0;

  constructor(private stationService: StationService, private fb: FormBuilder) {
    this.stationForm = this.fb.group({
      nom: ['', [Validators.required, Validators.maxLength(150)]],
      type: ['PRATIQUE', Validators.required],
      ordre: [1, [Validators.required, Validators.min(1)]],
      description: ['', Validators.maxLength(300)],
      grilleNom: [null, Validators.required] // Nouveau champ requis
    });
  }

  ngOnInit(): void {
    this.loadData();
    // Charger les modèles de grilles au démarrage
    this.stationService.getGrilleTemplates().subscribe(t => this.grilleTemplates = t);
  }

  loadData() {
    this.stationService.getStations().subscribe(data => {
      this.stations = data;
      this.totalStations = data.length;
    });
  }

  onSubmit() {
    if (this.stationForm.invalid) return;

    if (this.isEditing) {
      const index = this.stations.findIndex(s => s.id === this.currentStationId);
      this.stations[index] = { ...this.stations[index], ...this.stationForm.value };
      this.isEditing = false;
    } else {
      const newStation: StationUI = {
        id: Date.now(),
        ...this.stationForm.value
      };
      this.stations.push(newStation);
    }
    this.totalStations = this.stations.length;
    this.resetForm();
  }

  onEdit(station: StationUI) {
    this.isEditing = true;
    this.currentStationId = station.id;
    this.stationForm.patchValue(station);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  onDelete(id: number) {
    if (confirm('Supprimer cette station ?')) {
      this.stations = this.stations.filter(s => s.id !== id);
      this.totalStations = this.stations.length;
    }
  }

  resetForm() {
    this.isEditing = false;
    this.currentStationId = null;
    this.stationForm.reset({ type: 'PRATIQUE', ordre: this.stations.length + 1, grilleNom: null });
  }
}