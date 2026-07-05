import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ExamApiService } from '../../core/api/exam-api.service';
import { GrilleDetail, GrilleItem, GrilleTemplate, TypeItem } from '../../core/api/models';

const TYPE_ITEM_LABELS: Record<TypeItem, string> = {
  BINAIRE: 'Binaire',
  NUMERIQUE: 'Numérique',
};

const TYPE_ITEMS: TypeItem[] = ['BINAIRE', 'NUMERIQUE'];

/**
 * Grille editor for one station — the heaviest authoring surface. Hosted by
 * StationsGrillesComponent inside each station card, it owns the full lifecycle
 * of a station's single grille: create it (meta only), edit nom/noteMax, delete
 * it, and add / edit / delete its critères (items) with the BINAIRE vs NUMERIQUE
 * conditional valeurMax field.
 *
 * Server-truth choices (verified against GrilleServiceImpl, 2026-06-09):
 *  - A station holds at most ONE grille (unique constraint) → create is a single
 *    affordance that disappears once a grille exists.
 *  - Grilles are created meta-only. Grouped item creation (creerPourStation)
 *    bypasses both the NUMERIQUE valeurMax check and the pondération-sum check;
 *    only POST /items validates. So items are always added through the validated
 *    endpoint, one at a time.
 *  - `ordre` is server-assigned and re-sequenced on delete — never sent.
 *  - After any item mutation we re-GET the grille: ItemResponse carries no grille
 *    totals, and the server owns the recomputed ordre + ponderationValide flag.
 *  - The live "pondérations = noteMax" indicator mirrors the server
 *    `ponderationValide` flag (sum == noteMax within 0.001); the server itself
 *    only hard-blocks sum > noteMax, surfacing under-sum as the flag.
 *
 * All mutations are gated on [editable] (BROUILLON/CONFIGURE upstream); once
 * EN_COURS+ the editor renders read-only, matching the backend 403.
 */
@Component({
  selector: 'app-grille-editor',
  standalone: true,
  template: `
    @if (loading()) {
      <div class="h-16 rounded-lg bg-gray-100 animate-pulse"></div>
    } @else if (loadError()) {
      <div class="text-sm text-gray-500 flex items-center gap-3">
        <span>Impossible de charger la grille.</span>
        <button type="button" (click)="loadGrille()" class="text-brand hover:underline">
          Réessayer
        </button>
      </div>
    } @else {
      @if (grille(); as g) {
      <!-- ===== existing grille ===== -->
      <div class="rounded-lg bg-surface border border-gray-100 p-4 space-y-4">
        <!-- header / meta edit -->
        @if (editingMeta()) {
          <form [formGroup]="metaForm" (ngSubmit)="submitMeta()" novalidate class="space-y-3">
            <div class="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div class="sm:col-span-3">
                <label class="block text-xs font-medium text-gray-700 mb-1">Nom de la grille</label>
                <input
                  type="text"
                  formControlName="nom"
                  maxlength="150"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-700 mb-1">Note max</label>
                <input
                  type="number"
                  formControlName="noteMax"
                  min="1"
                  max="100"
                  step="0.5"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
              </div>
            </div>
            @if (metaNoteMaxBelowSum()) {
              <p class="text-xs text-status-warning">
                Attention : une note max ({{ metaForm.controls.noteMax.value }}) inférieure à la
                somme actuelle des pondérations ({{ sum() }}) laissera la grille incohérente.
              </p>
            }
            @if (metaError()) {
              <p role="alert" class="text-xs text-status-danger">{{ metaError() }}</p>
            }
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                (click)="cancelMeta()"
                class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                type="submit"
                [disabled]="metaForm.invalid || savingMeta()"
                class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ savingMeta() ? 'Enregistrement…' : 'Enregistrer' }}
              </button>
            </div>
          </form>
        } @else if (replacingGrille()) {
          <!-- #161 : remplacer la grille en un seul geste (create-or-replace, PUT) -->
          <form [formGroup]="replaceForm" (ngSubmit)="submitReplace()" novalidate class="space-y-3">
            <div class="text-sm font-semibold text-gray-900">Remplacer par une nouvelle grille</div>
            <p class="text-xs text-status-warning">
              Les {{ g.nombreItems ?? g.items?.length ?? 0 }} critère(s) actuels seront supprimés et
              remplacés par cette nouvelle grille (à repeupler ensuite).
            </p>
            <div class="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div class="sm:col-span-3">
                <label class="block text-xs font-medium text-gray-700 mb-1">Nom de la grille</label>
                <input
                  type="text"
                  formControlName="nom"
                  maxlength="150"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-700 mb-1">Note max</label>
                <input
                  type="number"
                  formControlName="noteMax"
                  min="1"
                  max="100"
                  step="0.5"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
              </div>
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-700 mb-1">
                Description <span class="text-gray-400">(optionnel)</span>
              </label>
              <textarea
                formControlName="description"
                rows="2"
                maxlength="300"
                class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
              ></textarea>
            </div>
            @if (replaceError()) {
              <p role="alert" class="text-xs text-status-danger">{{ replaceError() }}</p>
            }
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                (click)="cancelReplace()"
                class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                type="submit"
                [disabled]="replaceForm.invalid || replacingBusy()"
                class="px-3 py-1.5 rounded-lg bg-status-danger text-white text-sm font-medium hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ replacingBusy() ? 'Remplacement…' : 'Remplacer la grille' }}
              </button>
            </div>
          </form>
        } @else {
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <div class="text-sm font-medium text-gray-800">{{ g.nom }}</div>
              @if (g.description) {
                <p class="text-xs text-gray-500 mt-0.5">{{ g.description }}</p>
              }
            </div>
            @if (editable()) {
              <div class="flex items-center gap-2 shrink-0">
                <button type="button" (click)="openMeta()" class="text-xs text-gray-500 hover:text-brand">
                  Modifier
                </button>
                <button
                  type="button"
                  (click)="openReplace()"
                  class="text-xs text-gray-500 hover:text-brand"
                >
                  Remplacer
                </button>
                <button
                  type="button"
                  (click)="askDeleteGrille()"
                  class="text-xs text-gray-500 hover:text-status-danger"
                >
                  Supprimer
                </button>
              </div>
            }
          </div>
        }

        <!-- live pondération indicator -->
        <div
          class="flex items-center justify-between gap-3 rounded-lg px-3 py-2 text-sm"
          [class.bg-green-50]="valid()"
          [class.text-status-success]="valid()"
          [class.bg-amber-50]="!valid()"
          [class.text-status-warning]="!valid()"
        >
          <span class="font-medium">
            {{ g.nombreItems ?? g.items?.length ?? 0 }} critère(s)
          </span>
          <span class="tabular-nums">
            Pondérations {{ sum() }} / {{ g.noteMax ?? '—' }}
            @if (valid()) {
              <span class="ml-1">· ✓ complète</span>
            } @else {
              <span class="ml-1">· incomplète</span>
            }
          </span>
        </div>

        <!-- template actions: save this grille as a model / apply another -->
        @if (!editingMeta()) {
          @if (!savingTemplate() && !applyOpen()) {
            <div class="flex flex-wrap items-center gap-3">
              <button
                type="button"
                (click)="openSaveTemplate()"
                class="text-xs text-gray-600 hover:text-brand inline-flex items-center gap-1"
              >
                ↧ Enregistrer comme modèle
              </button>
              @if (editable()) {
                <button
                  type="button"
                  (click)="openApply()"
                  class="text-xs text-gray-600 hover:text-brand inline-flex items-center gap-1"
                >
                  ↥ Appliquer un modèle
                </button>
              }
            </div>
          }

          <!-- save-as-template form -->
          @if (savingTemplate()) {
            <form
              [formGroup]="templateNameForm"
              (ngSubmit)="submitSaveTemplate()"
              novalidate
              class="rounded-lg bg-surface border border-gray-100 p-3 space-y-2"
            >
              <label class="block text-xs font-medium text-gray-700">Nom du modèle</label>
              <div class="flex items-start gap-2">
                <input
                  type="text"
                  formControlName="nom"
                  maxlength="150"
                  placeholder="Ex. Grille standard — Préparation magistrale"
                  class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
                <button
                  type="submit"
                  [disabled]="templateNameForm.invalid || savingTemplateBusy()"
                  class="px-3 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {{ savingTemplateBusy() ? 'Enregistrement…' : 'Enregistrer' }}
                </button>
                <button
                  type="button"
                  (click)="cancelSaveTemplate()"
                  class="px-3 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
                >
                  Annuler
                </button>
              </div>
              @if (templateError()) {
                <p role="alert" class="text-xs text-status-danger">{{ templateError() }}</p>
              }
            </form>
          }

          @if (templateSaved(); as savedName) {
            <p class="text-xs text-status-success">
              Modèle « {{ savedName }} » enregistré dans la bibliothèque.
            </p>
          }

          <!-- apply a template onto THIS station (replaces the current grille) -->
          <ng-container
            [ngTemplateOutlet]="applyBlock"
            [ngTemplateOutletContext]="{ replacing: true }"
          ></ng-container>
        }

        <!-- grille delete confirm -->
        @if (confirmDeleteGrille()) {
          <div
            class="flex items-center justify-between gap-3 rounded-lg bg-red-50 border border-red-200 px-3 py-2"
          >
            <p class="text-sm text-status-danger">Supprimer la grille et tous ses critères ?</p>
            <div class="flex items-center gap-2 shrink-0">
              <button
                type="button"
                (click)="confirmDeleteGrille.set(false)"
                [disabled]="deletingGrille()"
                class="px-3 py-1 rounded-lg border border-gray-300 text-gray-700 text-xs font-medium hover:bg-gray-50 disabled:opacity-40"
              >
                Annuler
              </button>
              <button
                type="button"
                (click)="deleteGrille()"
                [disabled]="deletingGrille()"
                class="px-3 py-1 rounded-lg bg-status-danger text-white text-xs font-medium hover:opacity-90 disabled:opacity-40"
              >
                {{ deletingGrille() ? 'Suppression…' : 'Supprimer' }}
              </button>
            </div>
          </div>
          @if (grilleDeleteError()) {
            <p class="text-xs text-status-danger">{{ grilleDeleteError() }}</p>
          }
        }

        <!-- items -->
        @if (g.items?.length) {
          <ul class="divide-y divide-gray-100">
            @for (it of g.items; track it.id) {
              <li class="py-2">
                @if (editingItemId() === it.id) {
                  <ng-container [ngTemplateOutlet]="itemForm" [ngTemplateOutletContext]="{ mode: 'edit' }"></ng-container>
                } @else {
                  <div class="flex items-start justify-between gap-3 text-sm">
                    <span class="text-gray-700 min-w-0">
                      <span class="text-gray-400">{{ it.ordre ?? '·' }}.</span>
                      {{ it.libelle }}
                      @if (it.categorie) {
                        <span class="ml-1 text-xs text-gray-400">{{ it.categorie }}</span>
                      }
                      @if (it.conditionsAttendues) {
                        <span class="block text-xs text-gray-400 mt-0.5">
                          <span class="text-gray-500">Attendu :</span> {{ it.conditionsAttendues }}
                        </span>
                      }
                    </span>
                    <span class="flex items-center gap-2 shrink-0">
                      <span class="text-xs text-gray-500 text-right">
                        {{ typeItemLabel(it.type) }}
                        @if (it.type === 'NUMERIQUE' && it.valeurMax != null) {
                          · /{{ it.valeurMax }}
                        }
                        · pond. {{ it.ponderation ?? '—' }}
                      </span>
                      @if (editable()) {
                        <button
                          type="button"
                          (click)="openItemEdit(it)"
                          class="text-xs text-gray-500 hover:text-brand"
                        >
                          Modifier
                        </button>
                        <button
                          type="button"
                          (click)="askDeleteItem(it)"
                          class="text-xs text-gray-500 hover:text-status-danger"
                        >
                          Supprimer
                        </button>
                      }
                    </span>
                  </div>
                  @if (confirmDeleteItemId() === it.id) {
                    <div
                      class="flex items-center justify-between gap-3 rounded-lg bg-red-50 border border-red-200 px-3 py-1.5 mt-2"
                    >
                      <p class="text-xs text-status-danger">Supprimer ce critère ?</p>
                      <div class="flex items-center gap-2 shrink-0">
                        <button
                          type="button"
                          (click)="confirmDeleteItemId.set(null)"
                          [disabled]="deletingItemId() === it.id"
                          class="px-2.5 py-1 rounded-lg border border-gray-300 text-gray-700 text-xs hover:bg-gray-50 disabled:opacity-40"
                        >
                          Annuler
                        </button>
                        <button
                          type="button"
                          (click)="deleteItem(it)"
                          [disabled]="deletingItemId() === it.id"
                          class="px-2.5 py-1 rounded-lg bg-status-danger text-white text-xs hover:opacity-90 disabled:opacity-40"
                        >
                          {{ deletingItemId() === it.id ? '…' : 'Supprimer' }}
                        </button>
                      </div>
                    </div>
                    @if (itemDeleteError()) {
                      <p class="text-xs text-status-danger mt-1">{{ itemDeleteError() }}</p>
                    }
                  }
                }
              </li>
            }
          </ul>
        } @else {
          <p class="text-sm text-gray-400">Aucun critère. Ajoutez le premier ci-dessous.</p>
        }

        <!-- add item -->
        @if (editable()) {
          @if (addingItem()) {
            <ng-container [ngTemplateOutlet]="itemForm" [ngTemplateOutletContext]="{ mode: 'add' }"></ng-container>
          } @else {
            <button
              type="button"
              (click)="openItemAdd()"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-brand text-brand text-sm font-medium hover:bg-brand-50 transition-colors"
            >
              + Ajouter un critère
            </button>
          }
        }
      </div>
    } @else {
      <!-- ===== no grille yet ===== -->
      @if (editable()) {
        @if (creatingGrille()) {
          <form
            [formGroup]="createForm"
            (ngSubmit)="submitCreate()"
            novalidate
            class="rounded-lg bg-surface border border-gray-100 p-4 space-y-3"
          >
            <div class="text-sm font-semibold text-gray-900">Nouvelle grille d'évaluation</div>
            <div class="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div class="sm:col-span-3">
                <label class="block text-xs font-medium text-gray-700 mb-1">Nom de la grille</label>
                <input
                  type="text"
                  formControlName="nom"
                  maxlength="150"
                  placeholder="Ex. Grille — Préparation magistrale"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
                @if (createForm.controls.nom.touched && createForm.controls.nom.invalid) {
                  <p class="text-xs text-status-danger mt-1">Le nom est obligatoire (max 150).</p>
                }
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-700 mb-1">Note max</label>
                <input
                  type="number"
                  formControlName="noteMax"
                  min="1"
                  max="100"
                  step="0.5"
                  class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
                />
              </div>
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-700 mb-1">
                Description <span class="text-gray-400">(optionnel)</span>
              </label>
              <textarea
                formControlName="description"
                rows="2"
                maxlength="300"
                class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
              ></textarea>
            </div>
            @if (createError()) {
              <p role="alert" class="text-xs text-status-danger">{{ createError() }}</p>
            }
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                (click)="cancelCreate()"
                class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                type="submit"
                [disabled]="createForm.invalid || creating()"
                class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ creating() ? 'Création…' : 'Créer la grille' }}
              </button>
            </div>
          </form>
        } @else {
          <div class="space-y-3">
            @if (!applyOpen()) {
              <div class="flex items-center justify-between gap-3">
                <div class="flex items-center gap-2 text-sm text-gray-400">
                  <span class="w-2 h-2 rounded-full bg-status-warning"></span>
                  Aucune grille attachée à cette station.
                </div>
                <div class="flex items-center gap-2 shrink-0">
                  <button
                    type="button"
                    (click)="openApply()"
                    class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-brand text-brand text-sm font-medium hover:bg-brand-50 transition-colors"
                  >
                    Appliquer un modèle
                  </button>
                  <button
                    type="button"
                    (click)="openCreate()"
                    class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
                  >
                    + Créer une grille
                  </button>
                </div>
              </div>
            }
            <!-- apply onto a station that has no grille yet (neutral wording) -->
            <ng-container
              [ngTemplateOutlet]="applyBlock"
              [ngTemplateOutletContext]="{ replacing: false }"
            ></ng-container>
          </div>
        }
      } @else {
        <div class="flex items-center gap-2 text-sm text-gray-400">
          <span class="w-2 h-2 rounded-full bg-status-warning"></span>
          Aucune grille attachée à cette station.
        </div>
      }
      }
    }

    <!-- shared item form (add + inline edit) -->
    <ng-template #itemForm let-mode="mode">
      <form [formGroup]="itemFormGroup" (ngSubmit)="submitItem()" novalidate class="space-y-3">
        <div class="grid grid-cols-1 sm:grid-cols-6 gap-3">
          <div class="sm:col-span-3">
            <label class="block text-xs font-medium text-gray-700 mb-1">Libellé</label>
            <input
              type="text"
              formControlName="libelle"
              maxlength="300"
              placeholder="Ex. Hygiène des mains"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
            @if (itemFormGroup.controls.libelle.touched && itemFormGroup.controls.libelle.invalid) {
              <p class="text-xs text-status-danger mt-1">Le libellé est obligatoire (max 300).</p>
            }
          </div>
          <div>
            <label class="block text-xs font-medium text-gray-700 mb-1">Type</label>
            <select
              formControlName="type"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            >
              @for (t of typeItems; track t) {
                <option [value]="t">{{ typeItemLabel(t) }}</option>
              }
            </select>
          </div>
          <div>
            <label class="block text-xs font-medium text-gray-700 mb-1">Pondération</label>
            <input
              type="number"
              formControlName="ponderation"
              min="0.5"
              max="20"
              step="0.5"
              class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
            />
          </div>
          <!-- valeurMax: NUMERIQUE only -->
          @if (itemFormGroup.controls.type.value === 'NUMERIQUE') {
            <div>
              <label class="block text-xs font-medium text-gray-700 mb-1">Valeur max</label>
              <input
                type="number"
                formControlName="valeurMax"
                min="0.5"
                step="0.5"
                class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
              />
            </div>
          }
        </div>
        <!-- réponse attendue / corrigé (#162) — un seul champ libre : la réponse
             n'est pas toujours un nombre exact (intervalle, composé organique,
             tolérance, phrase). Comparé à la saisie lors des résultats. -->
        <div>
          <label class="block text-xs font-medium text-gray-700 mb-1">
            Réponse attendue <span class="font-normal text-gray-400">(corrigé — optionnel)</span>
          </label>
          <input
            type="text"
            formControlName="conditionsAttendues"
            maxlength="1000"
            [placeholder]="answerPlaceholder()"
            class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand focus:border-transparent"
          />
        </div>
        @if (valeurMaxExceedsPonderation()) {
          <p class="text-xs text-status-danger">
            La valeur max ne peut pas dépasser la pondération.
          </p>
        }
        @if (itemError()) {
          <p role="alert" class="text-xs text-status-danger">{{ itemError() }}</p>
        }
        <div class="flex items-center justify-end gap-2">
          <button
            type="button"
            (click)="cancelItem()"
            class="px-3 py-1.5 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-50"
          >
            Annuler
          </button>
          <button
            type="submit"
            [disabled]="itemFormGroup.invalid || valeurMaxExceedsPonderation() || savingItem()"
            class="px-3 py-1.5 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ savingItem() ? 'Enregistrement…' : mode === 'edit' ? 'Enregistrer' : 'Ajouter' }}
          </button>
        </div>
      </form>
    </ng-template>

    <!-- shared apply-a-template flow (picker → smart confirm). [replacing] drives
         the wording: a station with a grille warns about the full replace; an empty
         station gets neutral wording since nothing is lost. -->
    <ng-template #applyBlock let-replacing="replacing">
      @if (applyOpen()) {
        @if (templatesLoading()) {
          <div class="text-xs text-gray-500">Chargement des modèles…</div>
        } @else if (templatesLoadError()) {
          <div class="text-xs text-gray-500 flex items-center gap-2">
            <span>Impossible de charger les modèles.</span>
            <button type="button" (click)="openApply()" class="text-brand hover:underline">
              Réessayer
            </button>
            <button type="button" (click)="cancelApply()" class="text-gray-500 hover:underline">
              Annuler
            </button>
          </div>
        } @else {
          @if (selectedTemplate(); as t) {
            <!-- confirm -->
            <div
              class="rounded-lg border px-3 py-2 space-y-2"
              [class.bg-red-50]="replacing"
              [class.border-red-200]="replacing"
              [class.bg-surface]="!replacing"
              [class.border-gray-200]="!replacing"
            >
              @if (replacing) {
                <p class="text-sm text-status-danger">
                  Remplacer la grille actuelle par « {{ t.nom }} » ? Les critères existants seront
                  supprimés.
                </p>
              } @else {
                <p class="text-sm text-gray-700">
                  Appliquer « {{ t.nom }} » à cette station ?
                  <span class="text-gray-500">
                    ({{ t.nombreItems ?? t.items?.length ?? 0 }} critère(s) ·
                    {{ t.noteMax ?? '—' }} pts)
                  </span>
                </p>
              }
              @if (applyError()) {
                <p role="alert" class="text-xs text-status-danger">{{ applyError() }}</p>
              }
              <div class="flex items-center justify-end gap-2">
                <button
                  type="button"
                  (click)="cancelApply()"
                  [disabled]="applying()"
                  class="px-3 py-1 rounded-lg border border-gray-300 text-gray-700 text-xs font-medium hover:bg-gray-50 disabled:opacity-40"
                >
                  Annuler
                </button>
                <button
                  type="button"
                  (click)="confirmApply()"
                  [disabled]="applying()"
                  class="px-3 py-1 rounded-lg text-white text-xs font-medium hover:opacity-90 disabled:opacity-40"
                  [class.bg-status-danger]="replacing"
                  [class.bg-brand]="!replacing"
                >
                  {{ applying() ? '…' : replacing ? 'Remplacer' : 'Appliquer' }}
                </button>
              </div>
            </div>
          } @else {
            <!-- picker -->
            @if (templates(); as list) {
              @if (list.length === 0) {
                <div class="text-xs text-gray-500 flex items-center gap-2">
                  <span>Aucun modèle dans la bibliothèque pour le moment.</span>
                  <button type="button" (click)="cancelApply()" class="text-brand hover:underline">
                    Fermer
                  </button>
                </div>
              } @else {
                <div class="flex items-center gap-2">
                  <select
                    #tplPick
                    (change)="chooseTemplate(tplPick.value); tplPick.value = ''"
                    class="text-sm border border-gray-300 rounded-lg px-3 py-2 text-gray-700 bg-white focus:outline-none focus:ring-2 focus:ring-brand"
                  >
                    <option value="">Choisir un modèle…</option>
                    @for (t of list; track t.id) {
                      <option [value]="t.id">
                        {{ t.nom }} ({{ t.nombreItems ?? 0 }} crit. · {{ t.noteMax ?? '—' }} pts)
                      </option>
                    }
                  </select>
                  <button
                    type="button"
                    (click)="cancelApply()"
                    class="text-xs text-gray-500 hover:text-gray-700"
                  >
                    Annuler
                  </button>
                </div>
              }
            }
          }
        }
      }
    </ng-template>
  `,
  imports: [ReactiveFormsModule, NgTemplateOutlet],
})
export class GrilleEditorComponent {
  private readonly examApi = inject(ExamApiService);
  private readonly fb = inject(FormBuilder);

  readonly stationId = input.required<number>();
  readonly editable = input.required<boolean>();
  /** Initial hint from the station row, so we only GET when a grille exists. */
  readonly hasGrille = input.required<boolean>();

  /** Fires when a grille is created (true) or deleted (false) so the parent can
   *  flip the station's hasGrille without a full reload. */
  readonly existenceChanged = output<boolean>();

  readonly typeItems = TYPE_ITEMS;

  readonly grille = signal<GrilleDetail | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal(false);

  // create grille
  readonly creatingGrille = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly createForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // edit grille meta
  readonly editingMeta = signal(false);
  readonly savingMeta = signal(false);
  readonly metaError = signal<string | null>(null);
  readonly metaForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // replace grille from scratch (#161, single-gesture create-or-replace via PUT)
  readonly replacingGrille = signal(false);
  readonly replacingBusy = signal(false);
  readonly replaceError = signal<string | null>(null);
  readonly replaceForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
    noteMax: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
    description: ['', [Validators.maxLength(300)]],
  });

  // delete grille
  readonly confirmDeleteGrille = signal(false);
  readonly deletingGrille = signal(false);
  readonly grilleDeleteError = signal<string | null>(null);

  // item add / edit (one shared form)
  readonly addingItem = signal(false);
  readonly editingItemId = signal<number | null>(null);
  readonly savingItem = signal(false);
  readonly itemError = signal<string | null>(null);
  readonly itemFormGroup = this.fb.nonNullable.group({
    libelle: ['', [Validators.required, Validators.maxLength(300)]],
    type: ['BINAIRE' as TypeItem, [Validators.required]],
    ponderation: [1, [Validators.required, Validators.min(0.5), Validators.max(20)]],
    valeurMax: [null as number | null],
    categorie: ['', [Validators.maxLength(100)]],
    // Réponse attendue / corrigé (#162) — champ libre optionnel, tous types.
    conditionsAttendues: ['', [Validators.maxLength(1000)]],
  });

  // item delete
  readonly confirmDeleteItemId = signal<number | null>(null);
  readonly deletingItemId = signal<number | null>(null);
  readonly itemDeleteError = signal<string | null>(null);

  // save current grille as a template
  readonly savingTemplate = signal(false);
  readonly savingTemplateBusy = signal(false);
  readonly templateError = signal<string | null>(null);
  readonly templateSaved = signal<string | null>(null);
  readonly templateNameForm = this.fb.nonNullable.group({
    nom: ['', [Validators.required, Validators.maxLength(150)]],
  });

  // apply a template onto this station
  readonly applyOpen = signal(false);
  readonly templates = signal<GrilleTemplate[] | null>(null);
  readonly templatesLoading = signal(false);
  readonly templatesLoadError = signal(false);
  readonly selectedTemplate = signal<GrilleTemplate | null>(null);
  readonly applying = signal(false);
  readonly applyError = signal<string | null>(null);

  /** Live pondération sum, mirrored client-side from the committed items. */
  readonly sum = computed(() => {
    const items = this.grille()?.items ?? [];
    const total = items.reduce((acc, it) => acc + (it.ponderation ?? 0), 0);
    // Trim float noise (0.5 steps) for display.
    return Math.round(total * 100) / 100;
  });

  /** Mirror the server ponderationValide flag; fall back to a local compute. */
  readonly valid = computed(() => {
    const g = this.grille();
    if (!g) return false;
    if (g.ponderationValide != null) return g.ponderationValide;
    return Math.abs(this.sum() - (g.noteMax ?? 0)) < 0.001;
  });

  /** Warn when the meta form's noteMax drops below the existing pondération sum
   *  — the backend won't reject it (modifier skips the sum re-check). */
  readonly metaNoteMaxBelowSum = computed(() => {
    if (!this.editingMeta()) return false;
    const nm = Number(this.metaForm.controls.noteMax.value);
    return Number.isFinite(nm) && this.sum() > nm;
  });

  /** Cross-field: for NUMERIQUE, valeurMax must be ≤ ponderation (server rule). */
  readonly valeurMaxExceedsPonderation = computed(() => {
    const v = this.itemFormSignal();
    if (v.type !== 'NUMERIQUE') return false;
    const vm = Number(v.valeurMax);
    const p = Number(v.ponderation);
    return Number.isFinite(vm) && Number.isFinite(p) && vm > p;
  });

  /** Snapshot of the item form's value as a signal, to drive computed()s that
   *  must react to value changes (valueChanges → signal via a small effect). */
  private readonly itemFormSignal = signal(this.itemFormGroup.getRawValue());

  /** Type-aware hint for the free-text answer key: a numeric criterion usually
   *  expects a value/interval/tolerance, a binary one a compound or observation. */
  readonly answerPlaceholder = computed(() =>
    this.itemFormSignal().type === 'NUMERIQUE'
      ? 'ex. 4,5–5,5 mg/L, ou 300 mg ± 5 %'
      : 'ex. Paracétamol identifié, coloration violette observée',
  );

  constructor() {
    // Load the grille lazily the first time the editor is mounted for a station
    // that already has one. Re-runs if the bound stationId changes.
    effect(
      () => {
        const sid = this.stationId();
        if (this.hasGrille() && this.grille() === null && !this.loadError()) {
          this.loadGrilleFor(sid);
        }
      },
      { allowSignalWrites: true },
    );

    // Keep itemFormSignal in lockstep with the form, and toggle valeurMax's
    // required validator on the BINAIRE/NUMERIQUE switch.
    this.itemFormGroup.valueChanges.subscribe(() => {
      this.itemFormSignal.set(this.itemFormGroup.getRawValue());
    });
    this.itemFormGroup.controls.type.valueChanges.subscribe((type) => {
      const vm = this.itemFormGroup.controls.valeurMax;
      if (type === 'NUMERIQUE') {
        vm.setValidators([Validators.required, Validators.min(0.01)]);
      } else {
        vm.clearValidators();
        vm.setValue(null, { emitEvent: false });
      }
      vm.updateValueAndValidity({ emitEvent: false });
    });
  }

  loadGrille(): void {
    this.loadGrilleFor(this.stationId());
  }

  private loadGrilleFor(stationId: number): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.examApi.getStationGrille(stationId).subscribe({
      next: (grille) => {
        this.grille.set(grille ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  /** Quiet refetch after an item mutation, to pick up server ordre + totals. */
  private refetchGrille(): void {
    this.examApi.getStationGrille(this.stationId()).subscribe({
      next: (grille) => this.grille.set(grille ?? null),
    });
  }

  // ---- create grille ------------------------------------------------------

  openCreate(): void {
    this.createForm.reset({ nom: '', noteMax: 20, description: '' });
    this.createError.set(null);
    this.creatingGrille.set(true);
  }

  cancelCreate(): void {
    this.creatingGrille.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    if (this.createForm.invalid || this.creating()) return;
    const raw = this.createForm.getRawValue();
    this.creating.set(true);
    this.createError.set(null);
    this.examApi
      .createStationGrille(this.stationId(), {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (grille) => {
          this.creating.set(false);
          this.creatingGrille.set(false);
          this.grille.set(grille);
          this.existenceChanged.emit(true);
        },
        error: (err: HttpErrorResponse) => {
          this.creating.set(false);
          this.createError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- edit grille meta ---------------------------------------------------

  openMeta(): void {
    const g = this.grille();
    if (!g) return;
    this.metaForm.reset({
      nom: g.nom ?? '',
      noteMax: g.noteMax ?? 20,
      description: g.description ?? '',
    });
    this.metaError.set(null);
    this.editingMeta.set(true);
  }

  cancelMeta(): void {
    this.editingMeta.set(false);
    this.metaError.set(null);
  }

  submitMeta(): void {
    const g = this.grille();
    if (!g || this.metaForm.invalid || this.savingMeta()) return;
    const raw = this.metaForm.getRawValue();
    this.savingMeta.set(true);
    this.metaError.set(null);
    this.examApi
      .updateGrille(g.id, {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.savingMeta.set(false);
          this.editingMeta.set(false);
          this.grille.set(updated);
        },
        error: (err: HttpErrorResponse) => {
          this.savingMeta.set(false);
          this.metaError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- replace grille from scratch (#161) ---------------------------------

  openReplace(): void {
    // Start blank — a "remplacer" means a fresh grille, not an edit of the old one.
    this.editingMeta.set(false);
    this.confirmDeleteGrille.set(false);
    this.replaceForm.reset({ nom: '', noteMax: 20, description: '' });
    this.replaceError.set(null);
    this.replacingGrille.set(true);
  }

  cancelReplace(): void {
    this.replacingGrille.set(false);
    this.replaceError.set(null);
  }

  submitReplace(): void {
    if (this.replaceForm.invalid || this.replacingBusy()) return;
    const raw = this.replaceForm.getRawValue();
    this.replacingBusy.set(true);
    this.replaceError.set(null);
    // One idempotent PUT: overwrites meta + purges old critères in place. No
    // delete→create, so no unique station_id conflict is possible.
    this.examApi
      .replaceStationGrille(this.stationId(), {
        nom: raw.nom.trim(),
        noteMax: Number(raw.noteMax),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (grille) => {
          this.replacingBusy.set(false);
          this.replacingGrille.set(false);
          this.grille.set(grille);
          // Still exists (replaced, not deleted) — keep the parent row in sync.
          this.existenceChanged.emit(true);
        },
        error: (err: HttpErrorResponse) => {
          this.replacingBusy.set(false);
          this.replaceError.set(this.mutationMessage(err));
        },
      });
  }

  // ---- delete grille ------------------------------------------------------

  askDeleteGrille(): void {
    this.grilleDeleteError.set(null);
    this.confirmDeleteGrille.set(true);
  }

  deleteGrille(): void {
    const g = this.grille();
    if (!g || this.deletingGrille()) return;
    this.deletingGrille.set(true);
    this.grilleDeleteError.set(null);
    this.examApi.deleteGrille(g.id).subscribe({
      next: () => {
        this.deletingGrille.set(false);
        this.confirmDeleteGrille.set(false);
        this.grille.set(null);
        this.loadError.set(false);
        this.existenceChanged.emit(false);
      },
      error: (err: HttpErrorResponse) => {
        this.deletingGrille.set(false);
        this.grilleDeleteError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- items --------------------------------------------------------------

  openItemAdd(): void {
    this.editingItemId.set(null);
    this.itemError.set(null);
    this.itemFormGroup.reset({
      libelle: '',
      type: 'BINAIRE',
      ponderation: 1,
      valeurMax: null,
      categorie: '',
      conditionsAttendues: '',
    });
    this.addingItem.set(true);
  }

  openItemEdit(it: GrilleItem): void {
    this.addingItem.set(false);
    this.itemError.set(null);
    this.itemFormGroup.reset({
      libelle: it.libelle ?? '',
      type: it.type ?? 'BINAIRE',
      ponderation: it.ponderation ?? 1,
      valeurMax: it.valeurMax ?? null,
      categorie: it.categorie ?? '',
      conditionsAttendues: it.conditionsAttendues ?? '',
    });
    this.editingItemId.set(it.id);
  }

  cancelItem(): void {
    this.addingItem.set(false);
    this.editingItemId.set(null);
    this.itemError.set(null);
  }

  submitItem(): void {
    const g = this.grille();
    if (!g || this.itemFormGroup.invalid || this.valeurMaxExceedsPonderation() || this.savingItem()) {
      return;
    }
    const raw = this.itemFormGroup.getRawValue();
    const body = {
      libelle: raw.libelle.trim(),
      type: raw.type,
      ponderation: Number(raw.ponderation),
      valeurMax: raw.type === 'NUMERIQUE' ? Number(raw.valeurMax) : null,
      categorie: raw.categorie.trim() || undefined,
      conditionsAttendues: raw.conditionsAttendues.trim() || null,
    };
    this.savingItem.set(true);
    this.itemError.set(null);
    const editId = this.editingItemId();
    const req = editId
      ? this.examApi.updateGrilleItem(editId, body)
      : this.examApi.createGrilleItem(g.id, body);
    req.subscribe({
      next: () => {
        this.savingItem.set(false);
        this.addingItem.set(false);
        this.editingItemId.set(null);
        this.refetchGrille();
      },
      error: (err: HttpErrorResponse) => {
        this.savingItem.set(false);
        this.itemError.set(this.mutationMessage(err));
      },
    });
  }

  askDeleteItem(it: GrilleItem): void {
    this.itemDeleteError.set(null);
    this.confirmDeleteItemId.set(it.id);
  }

  deleteItem(it: GrilleItem): void {
    if (this.deletingItemId() === it.id) return;
    this.deletingItemId.set(it.id);
    this.itemDeleteError.set(null);
    this.examApi.deleteGrilleItem(it.id).subscribe({
      next: () => {
        this.deletingItemId.set(null);
        this.confirmDeleteItemId.set(null);
        this.refetchGrille();
      },
      error: (err: HttpErrorResponse) => {
        this.deletingItemId.set(null);
        this.itemDeleteError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- save as template ---------------------------------------------------

  openSaveTemplate(): void {
    const g = this.grille();
    this.applyOpen.set(false);
    this.templateSaved.set(null);
    this.templateError.set(null);
    // Seed the name with the grille's own nom — a sensible default the user edits.
    this.templateNameForm.reset({ nom: g?.nom ?? '' });
    this.savingTemplate.set(true);
  }

  cancelSaveTemplate(): void {
    this.savingTemplate.set(false);
    this.templateError.set(null);
  }

  submitSaveTemplate(): void {
    const g = this.grille();
    if (!g || this.templateNameForm.invalid || this.savingTemplateBusy()) return;
    const nom = this.templateNameForm.getRawValue().nom.trim();
    this.savingTemplateBusy.set(true);
    this.templateError.set(null);
    this.examApi.saveGrilleAsTemplate(g.id, nom).subscribe({
      next: (tpl) => {
        this.savingTemplateBusy.set(false);
        this.savingTemplate.set(false);
        this.templateSaved.set(tpl.nom);
        // Invalidate the cached picker list so a later apply sees the new model.
        this.templates.set(null);
      },
      error: (err: HttpErrorResponse) => {
        this.savingTemplateBusy.set(false);
        this.templateError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- apply a template ---------------------------------------------------

  openApply(): void {
    this.savingTemplate.set(false);
    this.templateSaved.set(null);
    this.selectedTemplate.set(null);
    this.applyError.set(null);
    this.applyOpen.set(true);
    if (this.templates() === null) this.loadTemplates();
  }

  cancelApply(): void {
    this.applyOpen.set(false);
    this.selectedTemplate.set(null);
    this.applyError.set(null);
  }

  private loadTemplates(): void {
    this.templatesLoading.set(true);
    this.templatesLoadError.set(false);
    this.examApi.listGrilleTemplates().subscribe({
      next: (list) => {
        this.templates.set(list);
        this.templatesLoading.set(false);
      },
      error: () => {
        this.templatesLoading.set(false);
        this.templatesLoadError.set(true);
      },
    });
  }

  chooseTemplate(rawId: string): void {
    const id = Number(rawId);
    if (!Number.isFinite(id) || id === 0) return;
    const t = (this.templates() ?? []).find((x) => x.id === id) ?? null;
    this.applyError.set(null);
    this.selectedTemplate.set(t);
  }

  confirmApply(): void {
    const t = this.selectedTemplate();
    if (!t || this.applying()) return;
    this.applying.set(true);
    this.applyError.set(null);
    this.examApi.applyTemplateToStation(t.id, this.stationId()).subscribe({
      next: () => {
        this.applying.set(false);
        this.applyOpen.set(false);
        this.selectedTemplate.set(null);
        this.loadError.set(false);
        // The backend deleted + recreated the grille; re-GET it and tell the parent
        // a grille now exists so the station row's hasGrille flips.
        this.refetchGrille();
        this.existenceChanged.emit(true);
      },
      error: (err: HttpErrorResponse) => {
        this.applying.set(false);
        this.applyError.set(this.mutationMessage(err));
      },
    });
  }

  // ---- helpers ------------------------------------------------------------

  /** Surface the backend BusinessException message (sum overflow, valeurMax…). */
  private mutationMessage(err: HttpErrorResponse): string {
    if (err.status === 400 || err.status === 409) {
      return typeof err.error?.message === 'string'
        ? err.error.message
        : 'Requête invalide. Vérifiez les valeurs saisies.';
    }
    if (err.status === 403) return "Vous n'avez pas les droits sur cet examen.";
    if (err.status === 404) return 'Ressource introuvable. Rechargez la page.';
    return "Échec de l'enregistrement. Réessayez.";
  }

  typeItemLabel(t: TypeItem | undefined): string {
    return t ? TYPE_ITEM_LABELS[t] : '';
  }
}
