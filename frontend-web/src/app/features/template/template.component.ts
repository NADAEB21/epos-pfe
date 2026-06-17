import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, FormArray, Validators, ReactiveFormsModule, FormsModule } from '@angular/forms';
import { TemplateService } from '../../core/template/template.service';
import { GrilleDetail } from '../../core/api/models';

@Component({
  selector: 'app-template',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './template.component.html',
  styleUrl: './template.component.scss'
})
export class TemplateComponent implements OnInit {
  templates: GrilleDetail[] = [];
  templateForm: FormGroup;
  isAdding = false;

  constructor(private ts: TemplateService, private fb: FormBuilder) {
    this.templateForm = this.fb.group({
      nom: ['', [Validators.required, Validators.minLength(3)]],
      description: [''],
      noteMax: [20, [Validators.required, Validators.min(1)]],
      items: this.fb.array([]) // Liste dynamique de GrilleItem
    });
  }

  ngOnInit(): void {
    this.ts.getTemplates().subscribe(data => this.templates = data);
  }

  // Accès rapide au FormArray des items
  get items() {
    return this.templateForm.get('items') as FormArray;
  }

  // Ajouter une ligne de critère vide
  addItemLine() {
    const itemGroup = this.fb.group({
      libelle: ['', Validators.required],
      type: ['BINAIRE', Validators.required],
      ponderation: [1, [Validators.required, Validators.min(0.5)]],
      categorie: ['Général'],
      ordre: [this.items.length + 1]
    });
    this.items.push(itemGroup);
  }

  removeItemLine(index: number) {
    this.items.removeAt(index);
  }

  // Calcul dynamique de la somme des points
  getCurrentTotal(): number {
    return this.items.value.reduce((sum: number, item: any) => sum + (item.ponderation || 0), 0);
  }

  onSubmit() {
    if (this.templateForm.invalid) return;
    
    const newTemplate: GrilleDetail = this.templateForm.value;
    this.ts.saveTemplate(newTemplate).subscribe(() => {
      this.templates.push(newTemplate);
      this.cancelForm();
    });
  }

  toggleAddMode() { this.isAdding = true; this.addItemLine(); }
  cancelForm() { this.isAdding = false; this.templateForm.reset({noteMax: 20}); this.items.clear(); }
}