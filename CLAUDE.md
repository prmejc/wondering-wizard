# CLAUDE.md - AI Assistant Guidelines for wondering-wizard

This document provides comprehensive guidance for AI assistants working on this repository.

## Project Overview

**wondering-wizard** is a single-file web application built with vanilla JavaScript and Web Components. The project emphasizes simplicity, browser-native APIs, and zero external dependencies.

## Core Principles

1. **Single-file architecture** - The entire application lives in `index.html`
2. **No external dependencies** - Use only vanilla JavaScript and native browser APIs
3. **Web Components** - All UI elements must be implemented as Web Components
4. **Browser-native** - Leverage modern browser features without polyfills or libraries

## Code Structure

### File Organization
```
wondering-wizard/
├── index.html          # Complete application (HTML, CSS, JS)
├── CLAUDE.md           # AI assistant guidelines (this file)
└── claude.md           # Legacy instructions
```

### Application Architecture

All code goes into `index.html` with this structure:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Wondering Wizard</title>
    <style>
        /* Global styles and CSS custom properties */
        /* Web Component styles should be encapsulated in Shadow DOM */
    </style>
</head>
<body>
    <!-- Application markup using custom elements -->

    <script>
        // Web Component definitions
        // Application logic
    </script>
</body>
</html>
```

## Web Components Guidelines

### Creating Components

All custom UI elements must extend `HTMLElement`:

```javascript
class MyComponent extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({ mode: 'open' });
    }

    connectedCallback() {
        // Called when element is added to DOM
        this.render();
    }

    disconnectedCallback() {
        // Called when element is removed from DOM
        // Clean up event listeners, observers, etc.
    }

    static get observedAttributes() {
        return ['attribute-name'];
    }

    attributeChangedCallback(name, oldValue, newValue) {
        // React to attribute changes
        if (oldValue !== newValue) {
            this.render();
        }
    }

    render() {
        this.shadowRoot.innerHTML = `
            <style>
                /* Scoped component styles */
            </style>
            <div>
                <!-- Component template -->
            </div>
        `;
    }
}

customElements.define('my-component', MyComponent);
```

### Component Best Practices

- **Use Shadow DOM** for style encapsulation
- **Define observed attributes** for reactive properties
- **Clean up** in `disconnectedCallback()` to prevent memory leaks
- **Use custom events** for parent-child communication
- **Keep components focused** on a single responsibility

### Naming Conventions

- Custom element names must contain a hyphen: `<wizard-card>`, `<spell-list>`
- Use lowercase with hyphens for element names
- Use PascalCase for class names: `WizardCard`, `SpellList`

## JavaScript Guidelines

### Allowed APIs

Use native browser APIs only:
- DOM APIs (`querySelector`, `createElement`, etc.)
- Fetch API for network requests
- Web Storage API (`localStorage`, `sessionStorage`)
- Custom Events
- Promises and async/await
- ES6+ features (classes, modules, destructuring, etc.)

### Prohibited

- External libraries (React, Vue, jQuery, etc.)
- npm packages
- CDN-hosted scripts
- Build tools (webpack, vite, etc.)

### Code Style

```javascript
// Use const/let, never var
const CONSTANTS = 'UPPER_SNAKE_CASE';
let mutableVariable = 'camelCase';

// Use arrow functions for callbacks
element.addEventListener('click', (e) => {
    // handler
});

// Use async/await over .then() chains
async function fetchData() {
    try {
        const response = await fetch('/api/data');
        return await response.json();
    } catch (error) {
        console.error('Fetch failed:', error);
    }
}

// Use template literals for HTML strings
const template = `
    <div class="container">
        <h1>${title}</h1>
    </div>
`;
```

## CSS Guidelines

### Global Styles

Define CSS custom properties at the `:root` level for theming:

```css
:root {
    --primary-color: #6B46C1;
    --secondary-color: #9F7AEA;
    --text-color: #1A202C;
    --background-color: #FFFFFF;
    --spacing-unit: 8px;
}
```

### Component Styles

Styles within Web Components should be scoped to Shadow DOM:

```javascript
this.shadowRoot.innerHTML = `
    <style>
        :host {
            display: block;
            /* Default styles for the component */
        }

        :host([hidden]) {
            display: none;
        }

        /* Component-specific styles */
    </style>
    <!-- template -->
`;
```

## Development Workflow

### Getting Started

1. Clone the repository
2. Open `index.html` directly in a browser, or
3. Use a simple local server: `python -m http.server 8000`

### Making Changes

1. Edit `index.html` directly
2. Refresh browser to see changes
3. Use browser DevTools for debugging

### Testing

- Manual testing in browser
- Use browser DevTools Console for debugging
- Test in multiple browsers if possible

## Git Conventions

### Branch Naming

- Feature branches: `feature/description`
- Bug fixes: `fix/description`
- Claude AI branches: `claude/session-identifier`

### Commit Messages

Write clear, concise commit messages:
- Use present tense: "Add feature" not "Added feature"
- Keep first line under 50 characters
- Add detailed description if needed after blank line

## Common Tasks

### Adding a New Component

1. Define the class extending `HTMLElement`
2. Implement lifecycle callbacks as needed
3. Register with `customElements.define()`
4. Use in HTML with the custom tag

### Adding Interactivity

1. Add event listeners in `connectedCallback()`
2. Remove listeners in `disconnectedCallback()`
3. Use custom events for component communication

### Styling

1. Use Shadow DOM for component encapsulation
2. Use CSS custom properties for theming
3. Keep global styles minimal

## Troubleshooting

### Component Not Rendering

- Verify custom element is registered before use
- Check for JavaScript errors in console
- Ensure `connectedCallback()` calls render method

### Styles Not Applying

- Check if styles are inside Shadow DOM
- Verify CSS selector specificity
- Use `:host` for component root styling

### Events Not Working

- Confirm listeners are added in `connectedCallback()`
- Check event bubbling through Shadow DOM (use `composed: true`)
- Verify event handler binding
