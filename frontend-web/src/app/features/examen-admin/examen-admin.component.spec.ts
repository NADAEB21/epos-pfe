import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ExamenAdminComponent } from './examen-admin.component';

describe('ExamenAdminComponent', () => {
  let component: ExamenAdminComponent;
  let fixture: ComponentFixture<ExamenAdminComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExamenAdminComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ExamenAdminComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
